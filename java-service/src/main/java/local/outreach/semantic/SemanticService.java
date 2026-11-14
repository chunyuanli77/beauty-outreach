package local.outreach.semantic;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import local.outreach.AuditLog;
import local.outreach.semantic.SemanticProvider.Metadata;
import local.outreach.semantic.SemanticProvider.Profile;
import local.outreach.semantic.SemanticProvider.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
final class SemanticService {
    private static final int LIMIT = 500;
    private static final int MAX_BATCH = 50;
    private static final Set<String> TRANSIENT_FAILURES = Set.of("RATE_LIMITED", "TIMEOUT", "PROVIDER_UNAVAILABLE");
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final SemanticProvider provider;
    private final TransactionTemplate tx;
    private final boolean autoEnabled;

    SemanticService(JdbcClient jdbc, ObjectMapper json, AuditLog audit, SemanticProvider provider,
                    TransactionTemplate tx, @Value("${outreach.semantic-auto-enabled:false}") boolean autoEnabled) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.provider = provider;
        this.tx = tx;
        this.autoEnabled = autoEnabled;
    }

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        tx.executeWithoutResult(transaction -> {
            lock();
            saveMetadata(Metadata.off());
            List<WorkKey> interrupted = jdbc.sql("SELECT job_id, lead_id FROM semantic_item WHERE status='RUNNING'")
                    .query((row, index) -> new WorkKey(row.getLong(1), row.getLong(2))).list();
            for (WorkKey key : interrupted) complete(key, "FAILED", "INTERRUPTED");
        });
        refreshProvider();
    }

    synchronized void refreshProvider() {
        Metadata metadata = provider.metadata();
        Metadata previous = current().metadata();
        if (metadata.configured() != previous.configured() || (metadata.configured() && !metadata.equals(previous))) {
            tx.executeWithoutResult(transaction -> {
                lock();
                saveMetadata(metadata);
            });
        }
    }

    Status status() {
        Config config = current();
        int used = usedToday();
        return new Status(config.available(), config.metadata().model(), config.metadata().promptVersion(),
                config.mode(), LIMIT, used, Math.max(0, LIMIT - used), config.autoEnabled(), config.blockedReason());
    }

    Status mode(String mode) {
        if (!"RULES".equals(mode) && !"SEMANTIC".equals(mode)) throw bad("INVALID_MODE");
        if (mode.equals("SEMANTIC")) refreshProvider();
        tx.executeWithoutResult(transaction -> {
            lock();
            Config cfg = current();
            if (mode.equals("SEMANTIC") && !cfg.available()) throw conflict(cfg.blockedReason() == null
                    ? "PROVIDER_NOT_CONFIGURED" : cfg.blockedReason());
            if (!mode.equals(cfg.mode())) {
                jdbc.sql("UPDATE semantic_settings SET mode=? WHERE id=1").param(mode).update();
                audit.record("SEMANTIC_MODE_CHANGED", null, null, mode);
            }
        });
        return status();
    }

    Job create(List<Long> input, String requestKey, Long retryJobId) {
        var ids = input == null ? List.<Long>of() : input;
        if (ids.size() > MAX_BATCH || ids.stream().anyMatch(id -> id == null || id <= 0)
                || (ids.isEmpty() && retryJobId == null) || (retryJobId != null && retryJobId <= 0)) {
            throw bad("INVALID_LEAD_IDS");
        }
        String key = requestKey == null ? null : requestKey.strip();
        if (key != null && (key.isEmpty() || key.length() > 128)) throw bad("INVALID_IDEMPOTENCY_KEY");
        var sorted = ids.stream().distinct().sorted().toList();
        String fingerprint = sorted + "/" + retryJobId;
        Long replay = key == null ? null : previous(key, fingerprint);
        if (replay != null) return job(replay);
        refreshProvider();
        long id = Objects.requireNonNull(tx.execute(transaction -> {
            long version = lock();
            Long existing = key == null ? null : previous(key, fingerprint);
            if (existing != null) return existing;
            Config cfg = current();
            if (!cfg.available()) throw conflict(cfg.blockedReason() == null
                    ? "PROVIDER_NOT_CONFIGURED" : cfg.blockedReason());
            if (usedToday() >= LIMIT) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT");
            List<Long> leads = retryJobId == null ? sorted : retryLeads(retryJobId, sorted);
            var generated = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO semantic_job(request_key, request_hash) VALUES (:key, :hash)")
                    .param("key", key, Types.VARCHAR).param("hash", fingerprint).update(generated, "ID");
            long jobId = Objects.requireNonNull(generated.getKey()).longValue();
            for (long leadId : leads) {
                ProfileRow row = jdbc.sql("SELECT bio, language, updated_at FROM lead WHERE id=?")
                        .param(leadId).query((result, index) -> new ProfileRow(
                                new Profile(result.getString(1), result.getString(2)),
                                result.getObject(3, OffsetDateTime.class))).optional()
                        .orElseThrow(() -> missing("LEAD_NOT_FOUND"));
                if (!provider.allows(row.profile())) throw bad("FIXTURE_NOT_ALLOWED");
                boolean active = jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM semantic_item
                        WHERE lead_id=? AND profile_time=? AND status IN ('PENDING', 'RUNNING'))
                        """).params(leadId, row.updatedAt()).query(Boolean.class).single();
                if (active) throw conflict("ANALYSIS_ALREADY_ACTIVE");
                int reused = jdbc.sql("""
                        INSERT INTO semantic_item(job_id, lead_id, profile_time, rule_version, model, prompt_version,
                            fixture_version, status, verdict, product, signal, purpose, explanation, evidence_json,
                            input_tokens, output_tokens)
                        SELECT :job, lead_id, profile_time, rule_version, model, prompt_version, fixture_version,
                            status, verdict, product, signal, purpose, explanation, evidence_json, 0, 0
                        FROM semantic_latest WHERE lead_id=:lead AND profile_time=:profile AND rule_version=:version
                            AND model=:model AND prompt_version=:prompt AND fixture_version=:fixture AND status='SUCCEEDED'
                        """).param("job", jobId).param("lead", leadId).param("profile", row.updatedAt())
                        .param("version", version).param("model", cfg.metadata().model())
                        .param("prompt", cfg.metadata().promptVersion()).param("fixture", provider.fixtureVersion()).update();
                if (reused == 1) {
                    audit.record("SEMANTIC_REUSED", null, leadId, "OK");
                    continue;
                }
                jdbc.sql("""
                        INSERT INTO semantic_item(job_id, lead_id, profile_time, rule_version, model, prompt_version, fixture_version, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                        """).params(jobId, leadId, row.updatedAt(), version, cfg.metadata().model(),
                        cfg.metadata().promptVersion(), provider.fixtureVersion()).update();
                audit.record("SEMANTIC_QUEUED", null, leadId, retryJobId == null ? "OK" : "RETRY");
            }
            return jobId;
        }));
        return job(id);
    }

    Job job(long id) {
        requireJob(id);
        List<Item> items = jdbc.sql("""
                SELECT lead_id, status, failure_reason, input_tokens, output_tokens FROM semantic_item
                WHERE job_id=? ORDER BY lead_id
                """).param(id).query((row, index) -> new Item(row.getLong(1), row.getString(2), row.getString(3),
                row.getInt(4), row.getInt(5))).list();
        int pending = 0;
        int running = 0;
        int succeeded = 0;
        int failed = 0;
        int stale = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        for (Item item : items) {
            switch (item.status()) {
                case "PENDING" -> pending++;
                case "RUNNING" -> running++;
                case "SUCCEEDED" -> succeeded++;
                case "FAILED" -> failed++;
                case "STALE" -> stale++;
                default -> throw new IllegalStateException("Invalid semantic status");
            }
            inputTokens += item.inputTokens();
            outputTokens += item.outputTokens();
        }
        String status = pending == items.size() ? "PENDING"
                : (pending > 0 || running > 0) ? "RUNNING"
                : succeeded == items.size() ? "SUCCEEDED" : succeeded == 0 ? (stale == items.size() ? "STALE" : "FAILED")
                : "PARTIAL";
        return new Job(id, status, items.size(), pending, running, succeeded, failed, stale, items, inputTokens, outputTokens);
    }

    List<Summary> summaries() {
        return jdbc.sql(RESULT_SQL + " ORDER BY l.id").query((row, index) ->
                new Summary(row.getLong("id"), row.getString("visible_status"), visibleVerdict(row),
                        provider.allows(new Profile(row.getString("bio"), row.getString("language"))))).list();
    }

    Detail detail(long leadId) {
        return jdbc.sql(RESULT_SQL + " AND l.id=?").param(leadId).query((row, index) -> {
            String verdict = visibleVerdict(row);
            boolean visible = verdict != null;
            return new Detail(leadId, row.getString("visible_status"), verdict,
                    visible ? row.getString("product") : null, visible ? row.getString("signal") : null,
                    visible ? row.getString("purpose") : null,
                    visible ? row.getString("explanation") : row.getString("visible_reason"),
                    visible ? decodeEvidence(row.getString("evidence_json")) : List.of(), row.getString("model"),
                    row.getString("prompt_version"), row.getObject("input_tokens", Integer.class),
                    row.getObject("output_tokens", Integer.class), row.getObject("updated_at", OffsetDateTime.class));
        }).optional().orElseThrow(() -> missing("LEAD_NOT_FOUND"));
    }

    Work claim() {
        return tx.execute(transaction -> {
            long version = lock();
            Config cfg = current();
            if (!cfg.available()) return null;
            var pending = jdbc.sql("""
                    SELECT i.job_id, i.lead_id, i.profile_time, l.updated_at, i.rule_version, i.model,
                           i.prompt_version, l.bio, l.language, i.fixture_version, i.budget_day IS NOT NULL
                    FROM semantic_item i JOIN lead l ON l.id=i.lead_id
                    WHERE i.status='PENDING' AND (i.retry_after IS NULL OR i.retry_after<=CURRENT_TIMESTAMP)
                    ORDER BY i.job_id, i.lead_id LIMIT 1
                    """).query((row, index) -> new Pending(new WorkKey(row.getLong(1), row.getLong(2)),
                    row.getObject(3, OffsetDateTime.class), row.getObject(4, OffsetDateTime.class),
                    row.getLong(5), row.getString(6), row.getString(7), new Profile(row.getString(8), row.getString(9)), row.getString(10), row.getBoolean(11)))
                    .optional().orElse(null);
            if (pending == null) return null;
            if (!pending.profileTime().isEqual(pending.currentTime()) || version != pending.ruleVersion()
                    || !cfg.metadata().model().equals(pending.model())
                    || !cfg.metadata().promptVersion().equals(pending.promptVersion())
                    || !Objects.equals(provider.fixtureVersion(), pending.fixtureVersion())) {
                complete(pending.key(), "STALE", "PROFILE_CHANGED");
                return null;
            }
            if (!provider.allows(pending.profile())) {
                complete(pending.key(), "FAILED", "FIXTURE_NOT_ALLOWED");
                return null;
            }
            LocalDate budgetDay = today();
            if (used(budgetDay) >= LIMIT) {
                stopPending("DAILY_LIMIT");
                return null;
            }
            jdbc.sql("""
                    UPDATE semantic_item SET status='RUNNING', budget_day=COALESCE(budget_day, :day),
                        retry_budget_day=CASE WHEN :retry THEN :day ELSE NULL END,
                        retry_after=NULL, failure_reason=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE job_id=:job AND lead_id=:lead
                    """).param("day", budgetDay).param("retry", pending.retry())
                    .param("job", pending.key().jobId()).param("lead", pending.key().leadId()).update();
            audit.system("SEMANTIC_STARTED", null, pending.key().leadId(), pending.retry() ? "RETRY" : "OK");
            return new Work(pending.key(), pending.profile(), pending.model(), pending.promptVersion(), pending.retry());
        });
    }

    void finish(Work work, Result result, String failure) {
        tx.executeWithoutResult(transaction -> {
            long version = lock();
            WorkKey key = work.key();
            Config cfg = current();
            if (failure != null) {
                if (Set.of("AUTH_FAILED", "BALANCE_EXHAUSTED").contains(failure)) {
                    jdbc.sql("UPDATE semantic_settings SET blocked_reason=? WHERE id=1").param(failure).update();
                    complete(key, "FAILED", failure);
                    stopPending(failure);
                } else if (cfg.blockedReason() == null && !work.retry() && TRANSIENT_FAILURES.contains(failure)) {
                    int updated = jdbc.sql("""
                            UPDATE semantic_item SET status='PENDING', failure_reason=?,
                                retry_after=DATEADD(SECOND, 3, CURRENT_TIMESTAMP), updated_at=CURRENT_TIMESTAMP
                            WHERE job_id=? AND lead_id=? AND status='RUNNING'
                            """).params(failure, key.jobId(), key.leadId()).update();
                    if (updated == 1) audit.system("SEMANTIC_RETRY_SCHEDULED", null, key.leadId(), failure);
                } else {
                    complete(key, "FAILED", failure);
                }
                return;
            }
            String evidence = json.valueToTree(result.evidence()).toString();
            int written = jdbc.sql("""
                    UPDATE semantic_item SET status='SUCCEEDED', verdict=:verdict, product=:product,
                        signal=:signal, purpose=:purpose, explanation=:reason, evidence_json=:evidence,
                        input_tokens=:input, output_tokens=:output, updated_at=CURRENT_TIMESTAMP
                    WHERE job_id=:job AND lead_id=:lead AND status='RUNNING'
                        AND profile_time=(SELECT updated_at FROM lead WHERE id=:lead)
                        AND rule_version=:version AND model=:model AND prompt_version=:prompt
                        AND fixture_version=:fixture
                    """).param("verdict", result.verdict()).param("product", result.product())
                    .param("signal", result.signal()).param("purpose", result.purpose()).param("reason", result.reason())
                    .param("evidence", evidence).param("input", result.inputTokens()).param("output", result.outputTokens())
                    .param("job", key.jobId()).param("lead", key.leadId()).param("version", version)
                    .param("model", cfg.metadata().model(), Types.VARCHAR)
                    .param("prompt", cfg.metadata().promptVersion(), Types.VARCHAR)
                    .param("fixture", provider.fixtureVersion(), Types.VARCHAR).update();
            if (written == 1) {
                audit.system("SEMANTIC_SUCCEEDED", null, key.leadId(), result.verdict());
            } else {
                jdbc.sql("UPDATE semantic_item SET input_tokens=?, output_tokens=? WHERE job_id=? AND lead_id=?")
                        .params(result.inputTokens(), result.outputTokens(), key.jobId(), key.leadId()).update();
                complete(key, "STALE", "PROFILE_CHANGED");
            }
        });
    }

    private void complete(WorkKey key, String status, String reason) {
        int updated = jdbc.sql("""
                UPDATE semantic_item SET status=?, failure_reason=?, updated_at=CURRENT_TIMESTAMP
                WHERE job_id=? AND lead_id=? AND status IN ('PENDING', 'RUNNING')
                """).params(status, reason, key.jobId(), key.leadId()).update();
        if (updated == 1) audit.system("SEMANTIC_" + status, null, key.leadId(), reason);
    }

    private void stopPending(String reason) {
        jdbc.sql("SELECT job_id, lead_id FROM semantic_item WHERE status='PENDING'")
                .query((row, index) -> new WorkKey(row.getLong(1), row.getLong(2))).list()
                .forEach(key -> complete(key, "FAILED", reason));
    }

    private List<Long> retryLeads(long previousId, List<Long> leads) {
        requireJob(previousId);
        List<Long> retryable = jdbc.sql("""
                SELECT lead_id FROM semantic_item WHERE job_id=? AND status IN ('FAILED', 'STALE') ORDER BY lead_id
                """).param(previousId).query(Long.class).list();
        if (retryable.isEmpty() || !retryable.containsAll(leads)) throw bad("INVALID_RETRY_TARGETS");
        return leads.isEmpty() ? retryable : leads;
    }

    private void requireJob(long id) {
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM semantic_job WHERE id=?)")
                .param(id).query(Boolean.class).single()) throw missing("JOB_NOT_FOUND");
    }

    private Long previous(String key, String fingerprint) {
        return jdbc.sql("SELECT id, request_hash FROM semantic_job WHERE request_key=?").param(key)
                .query((row, index) -> {
                    if (!fingerprint.equals(row.getString(2))) throw conflict("IDEMPOTENCY_KEY_CONFLICT");
                    return row.getLong(1);
                }).optional().orElse(null);
    }

    private void saveMetadata(Metadata metadata) {
        jdbc.sql("""
                UPDATE semantic_settings SET provider_ready=:ready, model=COALESCE(:model, model),
                    prompt_version=COALESCE(:prompt, prompt_version),
                    auto_enabled=:auto, fixture_version=:fixture WHERE id=1
                """).param("ready", metadata.configured()).param("model", metadata.model(), Types.VARCHAR)
                .param("prompt", metadata.promptVersion(), Types.VARCHAR).param("auto", autoEnabled)
                .param("fixture", provider.fixtureVersion(), Types.VARCHAR).update();
    }

    private Config current() {
        return jdbc.sql("SELECT mode, provider_ready, model, prompt_version, auto_enabled, blocked_reason FROM semantic_settings WHERE id=1")
                .query((row, index) -> new Config(row.getString(1),
                        new Metadata(row.getBoolean(2), row.getString(3), row.getString(4)), row.getBoolean(5), row.getString(6))).single();
    }

    private long lock() {
        long version = jdbc.sql("SELECT version FROM scoring_rules WHERE id=1 FOR UPDATE").query(Long.class).single();
        jdbc.sql("SELECT id FROM semantic_settings WHERE id=1 FOR UPDATE").query(Integer.class).single();
        return version;
    }

    private int usedToday() {
        return used(today());
    }

    private int used(LocalDate date) {
        return jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM semantic_item WHERE budget_day=:day)
                     + (SELECT COUNT(*) FROM semantic_item WHERE retry_budget_day=:day)
                """).param("day", date).query(Integer.class).single();
    }

    private static LocalDate today() {
        return LocalDate.now(CHINA);
    }

    private List<String> decodeEvidence(String evidence) {
        return json.convertValue(jsonRead(evidence), json.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private com.fasterxml.jackson.databind.JsonNode jsonRead(String evidence) {
        try {
            return json.readTree(evidence);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored semantic evidence", invalid);
        }
    }

    private static String visibleVerdict(java.sql.ResultSet row) throws java.sql.SQLException {
        return row.getString("visible_status").equals("SUCCEEDED") ? row.getString("verdict") : null;
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
    private static ResponseStatusException missing(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }
    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static final String RESULT_SQL = """
            SELECT l.id, l.bio, l.language, i.verdict, i.product, i.signal, i.purpose, i.explanation,
                   i.evidence_json, i.model, i.prompt_version, i.input_tokens, i.output_tokens, i.updated_at,
                   CASE WHEN i.job_id IS NULL THEN 'NOT_RUN'
                        WHEN i.profile_time<>l.updated_at OR i.rule_version<>r.version
                            OR i.fixture_version IS DISTINCT FROM c.fixture_version
                            OR (c.prompt_version IS NOT NULL AND (i.model<>c.model OR i.prompt_version<>c.prompt_version)) THEN 'STALE'
                        ELSE i.status END AS visible_status,
                   CASE WHEN i.profile_time<>l.updated_at OR i.rule_version<>r.version
                            OR i.fixture_version IS DISTINCT FROM c.fixture_version
                            OR (c.prompt_version IS NOT NULL AND (i.model<>c.model OR i.prompt_version<>c.prompt_version))
                        THEN 'PROFILE_CHANGED' ELSE i.failure_reason END AS visible_reason
            FROM lead l CROSS JOIN scoring_rules r CROSS JOIN semantic_settings c
            LEFT JOIN semantic_latest i ON i.lead_id=l.id
            WHERE r.id=1 AND c.id=1
            """;

    private record Config(String mode, Metadata metadata, boolean autoEnabled, String blockedReason) {
        boolean available() {
            return metadata.configured() && blockedReason == null;
        }
    }
    private record ProfileRow(Profile profile, OffsetDateTime updatedAt) {}
    private record Pending(WorkKey key, OffsetDateTime profileTime, OffsetDateTime currentTime, long ruleVersion,
                           String model, String promptVersion, Profile profile, String fixtureVersion, boolean retry) {}
    record WorkKey(long jobId, long leadId) {}
    record Work(WorkKey key, Profile profile, String model, String promptVersion, boolean retry) {}
    record Status(boolean configured, String model, String promptVersion, String mode, int dailyLimit,
                  int usedToday, int remainingToday, boolean autoEnabled, String blockedReason) {}
    record Item(long leadId, String status, String reason, int inputTokens, int outputTokens) {}
    record Job(long id, String status, int total, int pending, int running, int succeeded, int failed, int stale,
               List<Item> items, long inputTokens, long outputTokens) {}
    record Summary(long leadId, String status, String verdict, boolean semanticAllowed) {}
    record Detail(long leadId, String status, String verdict, String product, String signal, String purpose, String reason,
                  List<String> evidence, String model, String promptVersion, Integer inputTokens, Integer outputTokens,
                  OffsetDateTime updatedAt) {}
}
