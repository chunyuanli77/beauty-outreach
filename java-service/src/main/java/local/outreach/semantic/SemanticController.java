package local.outreach.semantic;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semantic")
final class SemanticController {
    private final SemanticService semantic;

    SemanticController(SemanticService semantic) {
        this.semantic = semantic;
    }

    @GetMapping("/status")
    SemanticService.Status status() {
        return semantic.status();
    }

    @PutMapping("/mode")
    SemanticService.Status mode(@RequestBody Mode input) {
        return semantic.mode(input.mode());
    }

    @PostMapping("/jobs")
    SemanticService.Job create(@RequestBody JobRequest input) {
        return semantic.create(input.leadIds(), input.idempotencyKey(), input.retryJobId());
    }

    @GetMapping("/jobs/{id}")
    SemanticService.Job job(@PathVariable long id) {
        return semantic.job(id);
    }

    @GetMapping("/leads")
    List<SemanticService.Summary> summaries() {
        return semantic.summaries();
    }

    @GetMapping("/leads/{id}")
    SemanticService.Detail detail(@PathVariable long id) {
        return semantic.detail(id);
    }

    record Mode(String mode) {}
    record JobRequest(List<Long> leadIds, String idempotencyKey, Long retryJobId) {}
}
