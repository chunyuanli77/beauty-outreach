package local.outreach;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Component
public class AuditLog {
    private final JdbcClient jdbc;

    AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, Long taskId, Long leadId, String reason) {
        record(action, taskId, leadId, reason, "local_operator", null);
    }

    public void system(String action, Long taskId, Long leadId, String reason) {
        system(action, taskId, leadId, reason, null);
    }

    public void system(String action, Long taskId, Long leadId, String reason, Integer attemptNo) {
        record(action, taskId, leadId, reason, "system", attemptNo);
    }

    private void record(String action, Long taskId, Long leadId, String reason, String actor, Integer attemptNo) {
        jdbc.sql("""
                INSERT INTO audit_event(action, task_id, lead_id, reason, actor, attempt_no, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """).params(java.util.Arrays.asList(action, taskId, leadId, reason, actor, attemptNo)).update();
    }

    List<Map<String, Object>> list(Long taskId, Long leadId, Integer attemptNo, Long beforeId, String action,
            OffsetDateTime since, OffsetDateTime until) {
        return jdbc.sql("""
                SELECT id AS "id", action AS "action", task_id AS "taskId", lead_id AS "leadId",
                       reason AS "reason", actor AS "actor", occurred_at AS "occurredAt", attempt_no AS "attemptNo"
                FROM audit_event
                WHERE (:task IS NULL OR task_id = :task) AND (:action IS NULL OR action = :action)
                  AND (:lead IS NULL OR lead_id = :lead) AND (:before IS NULL OR id < :before)
                  AND (:attempt IS NULL OR attempt_no = :attempt)
                  AND (:since IS NULL OR occurred_at >= :since) AND (:until IS NULL OR occurred_at <= :until)
                ORDER BY id DESC LIMIT 200
                """).param("task", taskId, java.sql.Types.BIGINT)
                .param("lead", leadId, java.sql.Types.BIGINT)
                .param("attempt", attemptNo, java.sql.Types.INTEGER)
                .param("before", beforeId, java.sql.Types.BIGINT)
                .param("action", action, java.sql.Types.VARCHAR)
                .param("since", since, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("until", until, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .query().listOfRows();
    }
}

@RestController
class AuditController {
    private final AuditLog audit;

    AuditController(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping("/api/audit")
    List<Map<String, Object>> list(@RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long leadId, @RequestParam(required = false) Integer attemptNo,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) String action, @RequestParam(required = false) OffsetDateTime since,
            @RequestParam(required = false) OffsetDateTime until) {
        return audit.list(taskId, leadId, attemptNo, beforeId, action, since, until);
    }
}
