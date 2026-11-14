package local.outreach;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import local.outreach.leads.LeadsService;
import local.outreach.leads.LeadsService.LeadInput;
import local.outreach.tasks.TaskController.ContactInput;
import local.outreach.tasks.TaskController.MockScenario;
import local.outreach.tasks.TaskService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DemoController {
    private final LeadsService leads;
    private final TaskService tasks;

    DemoController(LeadsService leads, TaskService tasks) {
        this.leads = leads;
        this.tasks = tasks;
    }

    @PostMapping("/api/demo")
    Map<String, Object> seed() {
        var fixtures = List.of(
                new Fixture("ready", true, false, false, MockScenario.SUCCESS),
                new Fixture("transient", true, false, false, MockScenario.TRANSIENT),
                new Fixture("permanent", true, false, false, MockScenario.PERMANENT),
                new Fixture("uncertain", true, false, false, MockScenario.UNCERTAIN),
                new Fixture("noConsent", false, false, false, MockScenario.SUCCESS),
                new Fixture("optedOut", true, true, false, MockScenario.SUCCESS),
                new Fixture("blacklisted", true, false, true, MockScenario.SUCCESS),
                new Fixture("noContact", false, false, false, null),
                new Fixture("lowScore", true, false, false, MockScenario.SUCCESS));
        var ids = new LinkedHashMap<String, Long>();
        for (var fixture : fixtures) {
            var label = fixture.label();
            String bio = label.equals("lowScore") ? "周末摄影和徒步" : "想试一款家用美容仪";
            var lead = new LeadInput("demo-" + label, "demo_" + label, bio, "CN", "zh",
                    "https://example.invalid/mock/" + label);
            long id = leads.importLeads(List.of(lead)).leadIds().getFirst();
            ids.put(label, id);
            if (fixture.scenario() != null) {
                tasks.ensureDemoContact(id, new ContactInput("MOCK-DEMO-" + id,
                        fixture.authorized(), fixture.unsubscribed(), fixture.blacklisted(), fixture.scenario()));
            }
        }
        return Map.of("templateId", tasks.ensureDemoTemplate(), "leadIds", ids);
    }

    private record Fixture(String label, boolean authorized, boolean unsubscribed, boolean blacklisted,
            MockScenario scenario) {}
}
