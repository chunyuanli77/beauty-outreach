package local.outreach.leads;

import java.text.Normalizer;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import local.outreach.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class ScoringRules {
    private static final TypeReference<List<String>> TERMS = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditLog audit;

    ScoringRules(JdbcClient jdbc, ObjectMapper json, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    Rules current() {
        return jdbc.sql("SELECT * FROM scoring_rules WHERE id = 1")
                .query((row, index) -> new Rules(row.getLong("version"),
                        decode(row.getString("core_json")), decode(row.getString("intent_json")),
                        decode(row.getString("exclude_json")), decode(row.getString("regions_json")),
                        decode(row.getString("languages_json")), row.getInt("threshold")))
                .single();
    }

    long lockVersion() {
        return jdbc.sql("SELECT version FROM scoring_rules WHERE id = 1 FOR UPDATE")
                .query(Long.class).single();
    }

    @Transactional
    public Rules update(RuleInput input) {
        if (input.threshold() == null || input.threshold() < 0 || input.threshold() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threshold must be an integer from 0 to 100");
        }
        List<String> core = clean(input.core());
        List<String> intent = clean(input.intent());
        List<String> exclude = clean(input.exclude());
        List<String> regions = clean(input.regions());
        List<String> languages = clean(input.languages());
        long version = lockVersion() + 1;
        jdbc.sql("""
                UPDATE scoring_rules SET version = ?, core_json = ?, intent_json = ?, exclude_json = ?,
                                         regions_json = ?, languages_json = ?, threshold = ? WHERE id = 1
                """).params(version, encode(core), encode(intent), encode(exclude), encode(regions),
                        encode(languages), input.threshold()).update();
        jdbc.sql("UPDATE lead SET score_eligible = NULL WHERE score_eligible IS NOT NULL").update();
        audit.record("RULES_UPDATED", null, null, "SCORING_CONFIG_UPDATED");
        return new Rules(version, core, intent, exclude, regions, languages, input.threshold());
    }

    private List<String> clean(List<String> terms) {
        if (terms == null || terms.size() > 100 || terms.stream().anyMatch(term -> term == null
                || term.length() > 120 || Normalizer.normalize(term, Normalizer.Form.NFKC).codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rule lists must contain at most 100 nonblank terms of up to 120 characters");
        }
        return terms.stream().map(String::strip).distinct().toList();
    }

    List<String> decode(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return json.readValue(value, TERMS);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid stored term list", failure);
        }
    }

    String encode(List<String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialize term list", failure);
        }
    }

    record RuleInput(List<String> core, List<String> intent, List<String> exclude,
            List<String> regions, List<String> languages, Integer threshold) {}

    record Rules(long version, List<String> core, List<String> intent, List<String> exclude,
            List<String> regions, List<String> languages, int threshold) {}
}
