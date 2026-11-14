package local.outreach.tasks;

import org.springframework.stereotype.Component;

@Component
class MockSender {
    Result send(String message, TaskController.MockScenario scenario, int attempt) {
        String outcome = switch (scenario) {
            case TRANSIENT -> attempt == 1 ? "TRANSIENT_ERROR" : "ACCEPTED";
            case PERMANENT -> "PERMANENT_ERROR";
            case UNCERTAIN -> "UNKNOWN_RESULT";
            case TRANSIENT_ALWAYS -> "TRANSIENT_ERROR";
            case SUCCESS -> "ACCEPTED";
        };
        return new Result(outcome, TaskService.hash(message));
    }

    record Result(String outcome, String messageSha256) {}
}
