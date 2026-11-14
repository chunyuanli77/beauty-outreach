package local.outreach.leads;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import local.outreach.AuditLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeadsService {
    private static final Set<String> CREDENTIAL_PARAMETERS = Set.of(
            "apikey", "auth", "authorization", "bearer", "clientsecret", "code", "credential",
            "credentials", "key", "password", "passwd", "passphrase", "secret", "session", "sessionid",
            "sid", "sig", "awsaccesskeyid", "googleaccessid", "oauthverifier", "xamzcredential", "xgoogcredential");
    private final JdbcClient jdbc;
    private final ScoringRules rules;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final RestClient scorer;

    LeadsService(JdbcClient jdbc, ScoringRules rules, AuditLog audit, TransactionTemplate transactions,
            @Value("${outreach.scoring-url:http://127.0.0.1:8001}") String scoringUrl) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.audit = audit;
        this.transactions = transactions;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(3000);
        this.scorer = RestClient.builder().baseUrl(scoringUrl).requestFactory(requestFactory).build();
    }

    @Transactional
    public ImportResult importLeads(List<LeadInput> leads) {
        if (leads == null || leads.isEmpty() || leads.size() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "import requires 1 to 500 leads");
        }
        // Task creation and contact updates use this lock before changing identity associations.
        jdbc.sql("SELECT id FROM frequency WHERE id=1 FOR UPDATE").query(Integer.class).single();
        rules.lockVersion();
        List<Long> leadIds = new ArrayList<>(leads.size());
        int created = 0;
        for (int index = 0; index < leads.size(); index++) {
            try {
                var row = importLead(leads.get(index));
                leadIds.add(row.id());
                if (row.created()) {
                    created++;
                }
            } catch (ResponseStatusException failure) {
                throw new ImportRowException(index + 1, failure);
            }
        }
        audit.record("LEADS_IMPORTED", null, null, "AUTHORIZED_OR_SYNTHETIC_IMPORT");
        return new ImportResult(leads.size(), leadIds, created, leads.size() - created);
    }

    private ImportedLead importLead(LeadInput lead) {
        if (lead == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lead must not be null");
        }
        String username = normalized(clean(lead.username(), 120, true, "username")).replaceFirst("^@", "");
        if (!username.matches("[a-zA-Z0-9_.]{1,120}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username must contain letters, digits, periods or underscores");
        }
        String usernameKey = username.toLowerCase(Locale.ROOT);
        String platformId = clean(lead.platformId(), 128, false, "platform_id");
        String bio = clean(lead.bio(), 4000, false, "bio");
        String region = clean(lead.region(), 100, false, "region");
        String language = clean(lead.language(), 40, false, "language");
        String sourceUrl = sourceUrl(lead.sourceUrl());

        Identity match = matchingLead(platformId, usernameKey, sourceUrl);
        boolean created = match == null;
        long id;
        if (created) {
            var key = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO lead(platform_id, username, username_key, bio, region, language)
                    VALUES (:platformId, :username, :usernameKey, COALESCE(:bio, ''), :region, :language)
                    """).param("platformId", platformId).param("username", username).param("usernameKey", usernameKey)
                    .param("bio", bio).param("region", region).param("language", language).update(key, "id");
            id = key.getKey().longValue();
        } else {
            id = match.id();
            if (platformId == null && match.platformId() != null) {
                return new ImportedLead(id, false);
            }
            String profileChanged = """
                    (bio IS DISTINCT FROM COALESCE(:bio, bio)
                        OR region IS DISTINCT FROM COALESCE(:region, region)
                        OR language IS DISTINCT FROM COALESCE(:language, language))
                    """.strip();
            jdbc.sql("""
                    UPDATE lead SET platform_id = COALESCE(:platformId, platform_id),
                                    username = :username, username_key = :usernameKey,
                                    bio = COALESCE(:bio, bio), region = COALESCE(:region, region),
                                    language = COALESCE(:language, language),
                                    score = CASE WHEN %1$s THEN NULL ELSE score END,
                                    hits_json = CASE WHEN %1$s THEN NULL ELSE hits_json END,
                                    score_eligible = CASE WHEN %1$s THEN NULL ELSE score_eligible END,
                                    rule_version = CASE WHEN %1$s THEN NULL ELSE rule_version END,
                                    updated_at = CURRENT_TIMESTAMP
                    WHERE id = :id AND (platform_id IS DISTINCT FROM COALESCE(:platformId, platform_id)
                        OR username IS DISTINCT FROM :username OR %1$s)
                    """.formatted(profileChanged)).param("platformId", platformId).param("username", username).param("usernameKey", usernameKey)
                    .param("bio", bio).param("region", region).param("language", language).param("id", id).update();
        }
        jdbc.sql("""
                INSERT INTO lead_source(lead_id, source_url) SELECT :id, :url
                WHERE NOT EXISTS (SELECT 1 FROM lead_source WHERE lead_id = :id AND source_url = :url)
                """).param("id", id).param("url", sourceUrl).update();
        return new ImportedLead(id, created);
    }

    private Identity matchingLead(String platformId, String usernameKey, String sourceUrl) {
        if (platformId != null) {
            Long confirmed = jdbc.sql("SELECT id FROM lead WHERE platform_id = ?").param(platformId)
                    .query(Long.class).optional().orElse(null);
            if (confirmed != null) {
                return new Identity(confirmed, platformId);
            }
        }
        List<Identity> matches = jdbc.sql("""
                SELECT l.id, l.platform_id, l.manual_decision,
                    EXISTS (SELECT 1 FROM lead_source s WHERE s.lead_id = l.id AND s.source_url = :source) AS same_source,
                    EXISTS (SELECT 1 FROM contact c WHERE c.lead_id = l.id) AS has_contact,
                    EXISTS (SELECT 1 FROM task_target t WHERE t.lead_id = l.id) AS has_target
                FROM lead l WHERE l.username_key = :username
                """).param("source", sourceUrl).param("username", usernameKey)
                .query((row, index) -> new Identity(row.getLong("id"), row.getString("platform_id"),
                        row.getBoolean("same_source"), row.getString("manual_decision") != null
                                || row.getBoolean("has_contact") || row.getBoolean("has_target"))).list();
        if (matches.isEmpty()) {
            return null;
        }
        if (platformId == null) {
            if (matches.size() == 1 && (matches.getFirst().platformId() == null || matches.getFirst().sameSource())) {
                return matches.getFirst();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "username already belongs to a platform account; provide platform_id");
        }
        var candidate = matches.getFirst();
        return matches.size() == 1 && candidate.platformId() == null && candidate.sameSource() && !candidate.hasHistory()
                ? candidate : null;
    }

    List<LeadView> list(String query, Boolean selected) {
        Map<Long, List<String>> sources = new HashMap<>();
        jdbc.sql("SELECT lead_id, source_url FROM lead_source ORDER BY id").query((row, index) -> {
            String source = row.getString(2);
            try {
                sourceUrl(source);
                sources.computeIfAbsent(row.getLong(1), key -> new ArrayList<>()).add(source);
            } catch (ResponseStatusException invalidSource) {
                // Keep legacy private links out of API responses without changing stored identity evidence.
            }
            return index;
        }).list();
        String search = query == null ? "" : searchable(query);
        return jdbc.sql("""
                SELECT l.*, s.selected FROM lead l JOIN lead_selection s ON s.lead_id=l.id
                ORDER BY l.score DESC NULLS LAST, l.id
                """)
                .query((row, index) -> new LeadView(row.getLong("id"), row.getString("platform_id"),
                        row.getString("username"), row.getString("bio"), row.getString("region"),
                        row.getString("language"), row.getObject("score", Integer.class),
                        rules.decode(row.getString("hits_json")), row.getObject("rule_version", Long.class),
                        row.getString("manual_decision"), row.getBoolean("selected"),
                        sources.getOrDefault(row.getLong("id"), List.of())))
                .list().stream()
                .filter(lead -> selected == null || lead.selected() == selected)
                .filter(lead -> search.isEmpty() || searchable(lead.username()).contains(search)
                        || searchable(lead.bio()).contains(search))
                .toList();
    }

    public ScoreSummary scoreAll() {
        ScoringRules.Rules active = rules.current();
        List<Profile> profiles = jdbc.sql("SELECT id, bio, region, language, updated_at FROM lead ORDER BY id")
                .query((row, index) -> new Profile(row.getLong("id"), row.getString("bio"), row.getString("region"),
                        row.getString("language"), row.getObject("updated_at", OffsetDateTime.class))).list();
        List<PendingScore> scores = new ArrayList<>();
        for (Profile profile : profiles) {
            scores.add(new PendingScore(profile, score(profile, active)));
        }
        return transactions.execute(status -> {
            if (rules.lockVersion() != active.version()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "scoring rules changed; retry with the current rules");
            }
            int updated = 0;
            for (PendingScore pending : scores) {
                ScoreResponse result = pending.result();
                Profile profile = pending.profile();
                updated += jdbc.sql("""
                        UPDATE lead SET score = ?, hits_json = ?, score_eligible = ?, rule_version = ?
                        WHERE id = ? AND updated_at = ?
                        """).params(result.score(), rules.encode(result.hits()), result.eligible(), result.ruleVersion(),
                                profile.id(), profile.updatedAt()).update();
            }
            audit.record("LEADS_SCORED", null, null, "SCORING_COMPLETED");
            return new ScoreSummary(updated, active.version());
        });
    }

    private ScoreResponse score(Profile profile, ScoringRules.Rules active) {
        ScoreResponse result;
        try {
            result = scorer.post().uri("/score")
                    .body(new ScoreRequest(profile.bio(), profile.region(), profile.language(), active))
                    .retrieve().body(ScoreResponse.class);
        } catch (RestClientException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "scoring service unavailable; retry scoring");
        }
        if (result == null || result.score() == null || result.score() < 0 || result.score() > 100
                || result.hits() == null || result.hits().stream().anyMatch(hit -> hit == null)
                || result.eligible() == null || result.ruleVersion() == null || result.ruleVersion() != active.version()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "scoring service returned an invalid result");
        }
        return result;
    }

    @Transactional
    public void decide(long id, String decision) {
        if (decision == null || !List.of("APPROVE", "REJECT", "AUTO").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be APPROVE, REJECT or AUTO");
        }
        rules.lockVersion();
        int updated = jdbc.sql("UPDATE lead SET manual_decision = :decision WHERE id = :id")
                .param("decision", "AUTO".equals(decision) ? null : decision)
                .param("id", id).update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found");
        }
        audit.record("LEAD_DECISION", null, id, decision);
    }

    private static String sourceUrl(String value) {
        String source = clean(value, 1024, true, "source_url");
        URI uri;
        try {
            uri = URI.create(source);
        } catch (IllegalArgumentException ignored) {
            throw invalidSourceUrl();
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw invalidSourceUrl();
        }
        if (hasCredentialParameter(uri.getRawQuery(), "[&;]") || hasCredentialParameter(uri.getRawFragment(), "[&;?]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "source_url contains a credential parameter; use a public link without credentials");
        }
        return source;
    }

    private static ResponseStatusException invalidSourceUrl() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "source_url must be a public HTTP or HTTPS URL");
    }

    private static boolean hasCredentialParameter(String parameters, String separators) {
        if (parameters == null) return false;
        for (String parameter : parameters.split(separators)) {
            String name = parameter.split("=", 2)[0];
            while (name.indexOf('%') >= 0 || name.indexOf('+') >= 0) {
                try {
                    String decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
                    if (decoded.equals(name)) break;
                    name = decoded;
                } catch (IllegalArgumentException malformedEscape) {
                    break;
                }
            }
            String key = normalized(name.split("=", 2)[0]).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (CREDENTIAL_PARAMETERS.contains(key) || key.endsWith("token") || key.endsWith("signature")) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String value, int max, boolean required, String field) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.length() > max || (required && trimmed.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is missing or too long");
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String searchable(String value) {
        return normalized(value).toLowerCase(Locale.ROOT);
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
    }

    public record LeadInput(
            @JsonProperty("platform_id") @JsonDeserialize(using = ImportText.class) String platformId,
            @JsonDeserialize(using = ImportText.class) String username,
            @JsonDeserialize(using = ImportText.class) String bio,
            @JsonDeserialize(using = ImportText.class) String region,
            @JsonDeserialize(using = ImportText.class) String language,
            @JsonProperty("source_url") @JsonDeserialize(using = ImportText.class) String sourceUrl) {}

    static class ImportText extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "lead field must be a string");
            }
            return parser.getText();
        }
    }

    public record ImportResult(int imported, @JsonProperty("lead_ids") List<Long> leadIds, int created, int matched) {}

    public record ScoreSummary(int scored, @JsonProperty("rule_version") long ruleVersion) {}

    record LeadView(long id, @JsonProperty("platform_id") String platformId, String username, String bio,
            String region, String language, Integer score, List<String> hits,
            @JsonProperty("rule_version") Long ruleVersion, @JsonProperty("manual_decision") String manualDecision,
            boolean selected, List<String> sources) {}

    private record ImportedLead(long id, boolean created) {}

    static class ImportRowException extends ResponseStatusException {
        ImportRowException(int row, ResponseStatusException failure) {
            super(failure.getStatusCode(), "Row " + row + ": " + failure.getReason());
        }
    }

    private record Identity(long id, String platformId, boolean sameSource, boolean hasHistory) {
        Identity(long id, String platformId) {
            this(id, platformId, false, false);
        }
    }

    private record Profile(long id, String bio, String region, String language, OffsetDateTime updatedAt) {}

    private record PendingScore(Profile profile, ScoreResponse result) {}

    private record ScoreRequest(String bio, String region, String language, ScoringRules.Rules rules) {}

    private record ScoreResponse(Integer score, List<String> hits, Boolean eligible,
            @JsonProperty("rule_version") Long ruleVersion) {}
}
