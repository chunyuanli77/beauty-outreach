package local.outreach.tasks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import local.outreach.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static local.outreach.tasks.TaskController.*;

@Service
public class TaskService {
    private static final String DISPLAY_NAME = "{{display_name}}";
    private static final Pattern BRACES = Pattern.compile("[{}]");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[a-zA-Z0-9._:-]{1,100}");
    private final JdbcClient jdbc;
    private final AuditLog audit;

    TaskService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    void lockDispatch() {
        jdbc.sql("SELECT id FROM frequency WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }

    List<Map<String, Object>> templates() {
        return jdbc.sql("""
                SELECT id AS "id", name AS "name", body AS "body", created_at AS "createdAt"
                FROM template ORDER BY id
                """).query().listOfRows();
    }

    @Transactional
    public Map<String, Object> createTemplate(TemplateInput input) {
        lockDispatch();
        if (BRACES.matcher(input.body().replace(DISPLAY_NAME, "")).find()) {
            throw failure(HttpStatus.BAD_REQUEST, "UNKNOWN_TEMPLATE_VARIABLE");
        }
        if (jdbc.sql("SELECT id FROM template WHERE name=?").param(input.name())
                .query(Long.class).optional().isPresent()) {
            throw failure(HttpStatus.CONFLICT, "TEMPLATE_NAME_EXISTS");
        }
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO template(name, body, created_at) VALUES (?, ?, ?)")
                .params(input.name(), input.body(), now()).update(key, "ID");
        audit.record("TEMPLATE_CREATED", null, null, "OK");
        return Map.of("id", key.getKey().longValue(), "name", input.name(), "body", input.body());
    }

    @Transactional
    public long ensureDemoTemplate() {
        lockDispatch();
        var existing = jdbc.sql("SELECT id FROM template WHERE name=?").param("Mock greeting")
                .query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        return ((Number) createTemplate(new TemplateInput("Mock greeting",
                "您好，{{display_name}}！这是一条模拟美容仪问候。")).get("id")).longValue();
    }

    @Transactional
    public Map<String, Object> updateContact(long leadId, ContactInput input) {
        lockDispatch();
        if (jdbc.sql("SELECT id FROM lead WHERE id=?").param(leadId)
                .query(Long.class).optional().isEmpty()) {
            throw failure(HttpStatus.NOT_FOUND, "LEAD_NOT_FOUND");
        }
        return saveContact(leadId, input);
    }

    @Transactional
    public Map<String, Object> changeContact(long leadId, String action) {
        lockDispatch();
        var current = jdbc.sql("""
                SELECT contact_key, authorized, unsubscribed, blacklisted, mock_scenario
                FROM contact WHERE lead_id=?
                """).param(leadId).query((rs, n) -> new ContactInput(rs.getString(1), rs.getBoolean(2),
                        rs.getBoolean(3), rs.getBoolean(4), MockScenario.valueOf(rs.getString(5))))
                .optional().orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "CONTACT_NOT_FOUND"));
        var update = switch (action) {
            case "AUTHORIZE" -> new ContactInput(current.contactKey(), true, current.unsubscribed(),
                    current.blacklisted(), current.mockScenario());
            case "UNSUBSCRIBE" -> new ContactInput(current.contactKey(), current.authorized(), true,
                    current.blacklisted(), current.mockScenario());
            default -> throw failure(HttpStatus.BAD_REQUEST, "INVALID_CONTACT_ACTION");
        };
        return saveContact(leadId, update, action.equals("AUTHORIZE") ? "AUTHORIZED" : "UNSUBSCRIBED");
    }

    private Map<String, Object> saveContact(long leadId, ContactInput input) {
        return saveContact(leadId, input, input.blacklisted() ? "BLACKLISTED"
                : input.unsubscribed() ? "UNSUBSCRIBED" : input.authorized() ? "AUTHORIZED" : "NO_CONSENT");
    }

    private Map<String, Object> saveContact(long leadId, ContactInput input, String requestedStatus) {
        jdbc.sql("""
                MERGE INTO contact(lead_id, contact_key, authorized, unsubscribed, blacklisted, mock_scenario, updated_at)
                KEY(lead_id) VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(leadId, input.contactKey(), input.authorized(), input.unsubscribed(), input.blacklisted(),
                input.mockScenario().name(), now()).update();
        var prior = suppression(input.contactKey());
        boolean unsubscribed = input.unsubscribed() || prior.unsubscribed();
        boolean blacklisted = input.blacklisted() || prior.blacklisted();
        if (unsubscribed || blacklisted) {
            jdbc.sql("""
                    MERGE INTO contact_suppression(contact_key, unsubscribed, blacklisted)
                    KEY(contact_key) VALUES (?, ?, ?)
                    """).params(input.contactKey(), unsubscribed, blacklisted).update();
        }
        if (input.unsubscribed() || input.blacklisted()) {
            jdbc.sql("""
                    MERGE INTO lead_suppression s USING (VALUES (?, ?, ?)) AS input(lead_id, unsubscribed, blacklisted)
                    ON s.lead_id=input.lead_id
                    WHEN MATCHED THEN UPDATE SET unsubscribed=s.unsubscribed OR input.unsubscribed,
                                                 blacklisted=s.blacklisted OR input.blacklisted
                    WHEN NOT MATCHED THEN INSERT (lead_id, unsubscribed, blacklisted)
                        VALUES (input.lead_id, input.unsubscribed, input.blacklisted)
                    """).params(leadId, input.unsubscribed(), input.blacklisted()).update();
        }
        audit.record("CONTACT_UPDATED", null, leadId, requestedStatus);
        var result = contact(leadId);
        audit.record("CONTACT_EFFECTIVE_STATUS", null, leadId, (String) result.get("effectiveStatus"));
        return result;
    }

    Map<String, Object> contact(long leadId) {
        var details = jdbc.sql("""
                SELECT c.contact_key AS "key", c.authorized AS "authorized",
                       (COALESCE(l.unsubscribed, FALSE) OR COALESCE(s.unsubscribed, FALSE) OR c.unsubscribed) AS "unsubscribed",
                       (COALESCE(l.unsubscribed, FALSE) OR c.unsubscribed) AS "leadUnsubscribed",
                       (COALESCE(l.blacklisted, FALSE) OR COALESCE(s.blacklisted, FALSE) OR c.blacklisted) AS "blacklisted",
                       c.mock_scenario AS "mockScenario", c.updated_at AS "updatedAt",
                       COALESCE(g.block_reason, 'AUTHORIZED') AS "effectiveStatus"
                FROM contact c LEFT JOIN contact_suppression s ON s.contact_key=c.contact_key
                LEFT JOIN lead_suppression l ON l.lead_id=c.lead_id
                JOIN lead_contact_gate g ON g.lead_id=c.lead_id
                WHERE c.lead_id=?
                """).param(leadId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "CONTACT_NOT_FOUND"));
        var response = new LinkedHashMap<>(details);
        response.put("leadId", leadId);
        response.put("contact", mask((String) response.remove("key")));
        response.put("channel", "WHATSAPP_MOCK");
        response.put("purpose", "GREETING_DEMO");
        return response;
    }

    @Transactional
    public void ensureDemoContact(long leadId, ContactInput input) {
        lockDispatch();
        if (jdbc.sql("SELECT lead_id FROM contact WHERE lead_id=?").param(leadId)
                .query(Long.class).optional().isEmpty()) {
            updateContact(leadId, input);
        }
    }

    Frequency frequency() {
        return jdbc.sql("SELECT global_limit, window_seconds, cooldown_seconds, retry_seconds FROM frequency WHERE id=1")
                .query((rs, n) -> new Frequency(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))).single();
    }

    @Transactional
    public Frequency updateFrequency(Frequency input) {
        lockDispatch();
        jdbc.sql("""
                UPDATE frequency SET global_limit=?, window_seconds=?, cooldown_seconds=?, retry_seconds=? WHERE id=1
                """).params(input.globalLimit(), input.windowSeconds(), input.cooldownSeconds(),
                input.retrySeconds()).update();
        audit.record("FREQUENCY_UPDATED", null, null, "OK");
        return input;
    }

    List<Map<String, Object>> list() {
        return jdbc.sql("""
                SELECT id AS "id", name AS "name", status AS "status",
                  template_id AS "templateId", created_at AS "createdAt", updated_at AS "updatedAt"
                FROM outreach_task ORDER BY id DESC LIMIT 200
                """).query().listOfRows();
    }

    @Transactional
    public Map<String, Object> create(String key, TaskInput input) {
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        }
        lockDispatch();
        var ids = input.leadIds().stream().distinct().sorted().toList();
        var hash = hash(input.templateId() + ":" + ids + ":" + input.name());
        var previous = jdbc.sql("""
                SELECT id, request_hash FROM outreach_task WHERE idempotency_key=?
                """).param(key).query((rs, n) -> Map.entry(rs.getLong(1), rs.getString(2))).optional();
        if (previous.isPresent()) {
            if (!hash.equals(previous.get().getValue())) {
                throw failure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");
            }
            return detail(previous.get().getKey());
        }

        String body = jdbc.sql("SELECT body FROM template WHERE id=?").param(input.templateId())
                .query(String.class).optional().orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND"));
        // Keep rules and lead decisions consistent across this batch's snapshot.
        jdbc.sql("SELECT version FROM scoring_rules WHERE id=1 FOR UPDATE").query(Long.class).single();
        var snapshots = new ArrayList<Snapshot>();
        for (long id : ids) {
            snapshots.add(jdbc.sql("""
                    SELECT l.id, l.username, c.contact_key, g.block_reason FROM lead l
                    JOIN lead_selection s ON s.lead_id=l.id
                    LEFT JOIN contact c ON c.lead_id=l.id
                    LEFT JOIN lead_contact_gate g ON g.lead_id=l.id
                    WHERE l.id=? AND s.selected=TRUE
                    """).param(id).query((rs, n) -> new Snapshot(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(3) == null ? "NO_CONTACT" : rs.getString(4)))
                    .optional().orElseThrow(() -> failure(HttpStatus.BAD_REQUEST, "LEAD_NOT_SELECTED")));
        }

        var taskKey = new GeneratedKeyHolder();
        var time = now();
        jdbc.sql("""
                INSERT INTO outreach_task(name, template_id, idempotency_key, request_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?)
                """).params(input.name(), input.templateId(), key, hash, time, time).update(taskKey, "ID");
        long taskId = taskKey.getKey().longValue();
        audit.record("TASK_CREATED", taskId, null, "OK");
        var representatives = new java.util.HashMap<String, Snapshot>();
        for (var snapshot : snapshots) {
            if (snapshot.contactKey() != null) {
                representatives.merge(snapshot.contactKey(), snapshot, (first, later) ->
                        first.blocked() != null && later.blocked() == null ? later : first);
            }
        }
        for (var snapshot : snapshots) {
            if (snapshot.contactKey() != null && representatives.get(snapshot.contactKey()) != snapshot) {
                audit.record("DUPLICATE_CONTACT", taskId, snapshot.leadId(),
                        snapshot.blocked() == null ? "DEDUPLICATED" : snapshot.blocked());
                continue;
            }
            var blocked = snapshot.blocked();
            jdbc.sql("""
                    INSERT INTO task_target(task_id, lead_id, contact_key, message_text, status, reason, next_at)
                    VALUES (:task, :lead, :contact, :message, :status, :reason, :due)
                    """).param("task", taskId).param("lead", snapshot.leadId())
                    .param("contact", snapshot.contactKey(), java.sql.Types.VARCHAR)
                    .param("message", body.replace(DISPLAY_NAME, snapshot.username()))
                    .param("status", blocked == null ? "PENDING" : "SKIPPED")
                    .param("reason", blocked == null ? "QUEUED" : blocked).param("due", time).update();
            if (blocked != null) {
                audit.record("TARGET_SKIPPED", taskId, snapshot.leadId(), blocked);
            }
        }
        return detail(taskId);
    }

    private Suppression suppression(String key) {
        return jdbc.sql("SELECT unsubscribed, blacklisted FROM contact_suppression WHERE contact_key=?")
                .param(key).query((rs, n) -> new Suppression(rs.getBoolean(1), rs.getBoolean(2)))
                .optional().orElseGet(() -> new Suppression(false, false));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id) {
        var result = new LinkedHashMap<>(jdbc.sql("""
                SELECT id AS "id", name AS "name", template_id AS "templateId", status AS "status",
                    created_at AS "createdAt", updated_at AS "updatedAt"
                FROM outreach_task WHERE id=?
                """).param(id).query().listOfRows().stream().findFirst().orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND")));
        var targets = jdbc.sql("""
                SELECT id AS "id", lead_id AS "leadId", status AS "status", reason AS "reason",
                    attempts AS "attempts", next_at AS "nextAt",
                    contact_key AS "contactKey", CAST(message_text AS VARCHAR) AS "messagePreview"
                FROM task_target WHERE task_id=? ORDER BY id
                """).param(id).query().listOfRows().stream().map(row -> {
                    var target = new LinkedHashMap<>(row);
                    target.put("contact", mask((String) target.remove("contactKey")));
                    int attempts = ((Number) target.get("attempts")).intValue();
                    target.put("retries", Math.max(0, attempts - 1));
                    return target;
                }).toList();
        var stats = new LinkedHashMap<String, Integer>();
        stats.put("total", targets.size());
        var snapshotHashes = new java.util.HashMap<Object, String>();
        for (var target : targets) {
            stats.merge((String) target.get("status"), 1, Integer::sum);
            snapshotHashes.put(target.get("id"), hash((String) target.get("messagePreview")));
        }
        result.put("stats", stats);
        result.put("targets", targets);
        result.put("attempts", jdbc.sql("""
                SELECT a.target_id AS "targetId", a.attempt_no AS "attemptNo", a.outcome AS "outcome",
                  a.attempted_at AS "attemptedAt", a.message_sha256 AS "messageSha256",
                  EXISTS (SELECT 1 FROM audit_event e WHERE e.task_id=t.task_id AND e.lead_id=t.lead_id
                        AND e.attempt_no=a.attempt_no) AS "auditAvailable"
                FROM send_attempt a
                JOIN task_target t ON t.id=a.target_id WHERE t.task_id=?
                ORDER BY a.attempted_at, a.target_id, a.attempt_no
                """).param(id).query().listOfRows().stream().map(row -> {
                    var attempt = new LinkedHashMap<>(row);
                    Object stored = attempt.get("messageSha256");
                    attempt.put("messageMatchesSnapshot", stored == null ? null : stored.equals(snapshotHashes.get(attempt.get("targetId"))));
                    return attempt;
                }).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> control(long id, String action) {
        lockDispatch();
        String before = jdbc.sql("SELECT status FROM outreach_task WHERE id=?").param(id)
                .query(String.class).optional().orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND"));
        String required = switch (action) {
            case "start" -> "DRAFT";
            case "pause" -> "RUNNING";
            default -> "PAUSED";
        };
        String after = action.equals("pause") ? "PAUSED" : "RUNNING";
        if (before.equals(after)) {
            return detail(id);
        }
        if (!before.equals(required)) {
            throw failure(HttpStatus.CONFLICT, "INVALID_TASK_TRANSITION");
        }
        jdbc.sql("UPDATE outreach_task SET status=?, updated_at=? WHERE id=?").params(after, now(), id).update();
        audit.record("TASK_" + action.toUpperCase(java.util.Locale.ROOT), id, null, after);
        return detail(id);
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String mask(String contactKey) {
        if (contactKey == null) {
            return null;
        }
        return contactKey.length() <= 8 ? "****" : "****" + contactKey.substring(contactKey.length() - 3);
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static ResponseStatusException failure(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    private record Suppression(boolean unsubscribed, boolean blacklisted) {}
    private record Snapshot(long leadId, String username, String contactKey, String blocked) {}
}
