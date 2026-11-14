package local.outreach;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class OutreachApplication {
    public static void main(String[] args) {
        SpringApplication.run(OutreachApplication.class, args);
    }
}

@RestController
class HealthController {
    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
