package local.outreach.tasks;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

import local.outreach.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DispatchWorker {
    private final JdbcClient jdbc;
    private final TaskService tasks;
    private final AuditLog audit;
    private final MockSender sender;

    DispatchWorker(JdbcClient jdbc, TaskService tasks, AuditLog audit, MockSender sender) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.audit = audit;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${outreach.dispatch-delay-ms:400}", initialDelayString = "${outreach.dispatch-delay-ms:400}")
    @Transactional
    public void tick() {
        tasks.lockDispatch();
        var now = TaskService.now();
        settleReceipt(now);
        var frequency = tasks.frequency();
        var cutoff = now.minusSeconds(frequency.windowSeconds());
        int used = jdbc.sql("SELECT COUNT(*) FROM send_attempt WHERE attempted_at > ?").param(cutoff)
                .query(Integer.class).single();
        skipBlocked(now);
        if (used < frequency.globalLimit()) {
            jdbc.sql("""
                    SELECT t.id, t.task_id, t.lead_id, t.contact_key, t.attempts, c.mock_scenario, t.message_text
                    FROM task_target t
                    JOIN outreach_task j ON j.id=t.task_id
                    JOIN contact c ON c.lead_id=t.lead_id AND c.contact_key=t.contact_key
                    JOIN lead_contact_gate g ON g.lead_id=c.lead_id AND g.block_reason IS NULL
                    WHERE j.status='RUNNING' AND t.status IN ('PENDING','RETRY') AND t.next_at <= :due
                      AND NOT EXISTS (SELECT 1 FROM send_attempt a WHERE a.contact_key=t.contact_key
                          AND a.attempted_at > :cooldown)
                    ORDER BY t.next_at, t.id LIMIT 1
                    """).param("due", now).param("cooldown", now.minusSeconds(frequency.cooldownSeconds()))
                    .query((rs, n) -> new Dispatch(target(rs), TaskController.MockScenario.valueOf(rs.getString(6)),
                            rs.getString(7)))
                    .optional().ifPresent(item -> dispatch(item, now, frequency));
        }
        var completed = jdbc.sql("""
                SELECT j.id FROM outreach_task j WHERE j.status='RUNNING' AND NOT EXISTS (
                  SELECT 1 FROM task_target t WHERE t.task_id=j.id AND t.status IN ('PENDING','RETRY','ACCEPTED'))
                """).query(Long.class).list();
        for (long id : completed) {
            jdbc.sql("UPDATE outreach_task SET status='COMPLETED', updated_at=? WHERE id=?").params(now, id).update();
            audit.system("TASK_COMPLETED", id, null, "OK");
        }
    }

    private void skipBlocked(OffsetDateTime now) {
        var blocked = jdbc.sql("""
                SELECT t.id, t.task_id, t.lead_id, t.contact_key, t.attempts,
                       CASE WHEN g.block_reason IN ('BLACKLISTED','UNSUBSCRIBED') THEN g.block_reason
                            WHEN c.lead_id IS NULL THEN 'CONTACT_CHANGED' ELSE g.block_reason END
                FROM task_target t
                JOIN outreach_task j ON j.id=t.task_id
                LEFT JOIN contact c ON c.lead_id=t.lead_id AND c.contact_key=t.contact_key
                LEFT JOIN lead_contact_gate g ON g.lead_id=t.lead_id
                WHERE j.status='RUNNING' AND t.status IN ('PENDING','RETRY')
                  AND (c.lead_id IS NULL OR g.block_reason IS NOT NULL)
                ORDER BY t.id LIMIT 100
                """).query((rs, n) -> new BlockedTarget(target(rs), rs.getString(6))).list();
        for (var item : blocked) {
            update(item.target(), "SKIPPED", item.reason(), item.target().attempts(), now);
        }
    }

    private void settleReceipt(OffsetDateTime now) {
        var receipts = jdbc.sql("""
                SELECT t.id, t.task_id, t.lead_id, t.contact_key, t.attempts
                FROM mock_receipt r JOIN task_target t ON t.id=r.target_id
                WHERE r.delivered_at IS NULL AND r.due_at <= ?
                ORDER BY r.due_at, t.id LIMIT 20
                """).param(now).query((rs, n) -> target(rs)).list();
        for (var target : receipts) {
            jdbc.sql("UPDATE mock_receipt SET delivered_at=? WHERE target_id=?").params(now, target.id()).update();
            update(target, "DELIVERED", "MOCK_RECEIPT", target.attempts(), now);
        }
    }

    private void dispatch(Dispatch dispatch, OffsetDateTime now, TaskController.Frequency frequency) {
        var target = dispatch.target();
        int attempt = target.attempts() + 1;
        var result = sender.send(dispatch.message(), dispatch.scenario(), attempt);
        jdbc.sql("""
                INSERT INTO send_attempt(target_id, attempt_no, contact_key, outcome, attempted_at, message_sha256)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(target.id(), attempt, target.contactKey(), result.outcome(), now, result.messageSha256()).update();

        switch (result.outcome()) {
            case "ACCEPTED" -> {
                jdbc.sql("INSERT INTO mock_receipt(target_id, due_at) VALUES (?, ?)")
                        .params(target.id(), now.plusSeconds(1)).update();
                update(target, "ACCEPTED", "MOCK_ACCEPTED", attempt, now);
            }
            case "TRANSIENT_ERROR" -> {
                if (attempt >= 3) {
                    update(target, "FAILED", "RETRY_EXHAUSTED", attempt, now);
                } else {
                    update(target, "RETRY", result.outcome(), attempt, now.plusSeconds((long) frequency.retrySeconds() * attempt));
                }
            }
            case "PERMANENT_ERROR" -> update(target, "FAILED", result.outcome(), attempt, now);
            default -> update(target, "UNCERTAIN", result.outcome(), attempt, now);
        }
    }

    private void update(Target target, String status, String reason, int attempts, OffsetDateTime next) {
        jdbc.sql("UPDATE task_target SET status=?, reason=?, attempts=?, next_at=? WHERE id=?")
                .params(status, reason, attempts, next, target.id()).update();
        audit.system("TARGET_" + status, target.taskId(), target.leadId(), reason,
                "SKIPPED".equals(status) ? null : attempts);
    }

    private static Target target(ResultSet row) throws SQLException {
        return new Target(row.getLong(1), row.getLong(2), row.getLong(3), row.getString(4), row.getInt(5));
    }

    private record Target(long id, long taskId, long leadId, String contactKey, int attempts) {}
    private record Dispatch(Target target, TaskController.MockScenario scenario, String message) {}
    private record BlockedTarget(Target target, String reason) {}
}
