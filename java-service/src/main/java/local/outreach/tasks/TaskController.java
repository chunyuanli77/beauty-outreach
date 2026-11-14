package local.outreach.tasks;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaskController {
    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    public record TemplateInput(@NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 1000) String body) {}

    public record ContactInput(@NotNull @Pattern(regexp = "MOCK-[A-Z0-9-]{1,48}") String contactKey,
            @NotNull Boolean authorized, @NotNull Boolean unsubscribed, @NotNull Boolean blacklisted,
            @NotNull MockScenario mockScenario) {}

    public record ContactPatch(@NotNull @Pattern(regexp = "AUTHORIZE|UNSUBSCRIBE") String action) {}

    public enum MockScenario { SUCCESS, TRANSIENT, TRANSIENT_ALWAYS, PERMANENT, UNCERTAIN }

    public record Frequency(@Min(1) @Max(100) int globalLimit,
            @Min(1) @Max(3600) int windowSeconds, @NotNull @Min(0) @Max(86400) Integer cooldownSeconds,
            @Min(1) @Max(3600) int retrySeconds) {}

    public record TaskInput(@NotBlank @Size(max = 120) String name, @Positive long templateId,
            @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> leadIds) {}

    @GetMapping("/templates")
    List<Map<String, Object>> templates() {
        return tasks.templates();
    }

    @PostMapping("/templates")
    Map<String, Object> template(@RequestBody @Valid TemplateInput input) {
        return tasks.createTemplate(input);
    }

    @GetMapping("/contacts/{leadId}")
    Map<String, Object> contact(@PathVariable long leadId) {
        return tasks.contact(leadId);
    }

    @PutMapping("/contacts/{leadId}")
    Map<String, Object> contact(@PathVariable long leadId, @RequestBody @Valid ContactInput input) {
        return tasks.updateContact(leadId, input);
    }

    @PatchMapping("/contacts/{leadId}")
    Map<String, Object> contact(@PathVariable long leadId, @RequestBody @Valid ContactPatch input) {
        return tasks.changeContact(leadId, input.action());
    }

    @GetMapping("/frequency")
    Frequency frequency() {
        return tasks.frequency();
    }

    @PutMapping("/frequency")
    Frequency frequency(@RequestBody @Valid Frequency input) {
        return tasks.updateFrequency(input);
    }

    @GetMapping("/tasks")
    List<Map<String, Object>> list() {
        return tasks.list();
    }

    @PostMapping("/tasks")
    Map<String, Object> create(@RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid TaskInput input) {
        return tasks.create(key, input);
    }

    @GetMapping("/tasks/{id}")
    Map<String, Object> detail(@PathVariable long id) {
        return tasks.detail(id);
    }

    @PostMapping("/tasks/{id}/{action:start|pause|resume}")
    Map<String, Object> control(@PathVariable long id, @PathVariable String action) {
        return tasks.control(id, action);
    }
}
