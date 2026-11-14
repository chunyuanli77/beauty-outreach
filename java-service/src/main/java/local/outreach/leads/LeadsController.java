package local.outreach.leads;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LeadsController {
    private final LeadsService leads;
    private final ScoringRules rules;

    LeadsController(LeadsService leads, ScoringRules rules) {
        this.leads = leads;
        this.rules = rules;
    }

    @PostMapping(path = "/api/leads/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    LeadsService.ImportResult importLeads(@RequestBody List<LeadsService.LeadInput> input) {
        return leads.importLeads(input);
    }

    @ExceptionHandler(LeadsService.ImportRowException.class)
    ResponseEntity<Map<String, String>> importRowError(LeadsService.ImportRowException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("message", failure.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> malformedImport(HttpMessageNotReadableException failure,
            HttpServletRequest request) {
        if (!request.getRequestURI().equals(request.getContextPath() + "/api/leads/import")) {
            throw failure;
        }
        String message = "Import body must be a JSON array of lead objects";
        if (failure.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            int index = mapping.getPath().getFirst().getIndex();
            if (index >= 0) {
                message = "Row " + (index + 1) + ": invalid lead object or field type";
            }
        }
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @PostMapping("/api/leads/score")
    LeadsService.ScoreSummary score() {
        return leads.scoreAll();
    }

    @GetMapping("/api/leads")
    List<LeadsService.LeadView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean selected) {
        return leads.list(q, selected);
    }

    @PutMapping("/api/leads/{id}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decide(@PathVariable long id, @RequestBody LeadDecision input) {
        leads.decide(id, input.decision());
    }

    @GetMapping("/api/rules")
    ScoringRules.Rules rules() {
        return rules.current();
    }

    @PutMapping("/api/rules")
    ScoringRules.Rules updateRules(@RequestBody ScoringRules.RuleInput input) {
        return rules.update(input);
    }

    record LeadDecision(String decision) {}
}
