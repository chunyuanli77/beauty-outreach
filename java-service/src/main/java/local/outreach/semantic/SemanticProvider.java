package local.outreach.semantic;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class SemanticProvider {
    private final ObjectMapper json;
    private final Set<Profile> fixture;
    private final URI base;
    private final String fixtureVersion;
    private final String internalToken;
    private final HttpClient http = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).build();

    SemanticProvider(ObjectMapper json,
            @Value("${outreach.scoring-url:http://127.0.0.1:8001}") String scoringUrl,
            @Value("${outreach.semantic-fixture-path:}") String fixturePath,
            @Value("${outreach.semantic-internal-token:}") String internalToken) throws IOException {
        this.json = json;
        this.base = URI.create(scoringUrl);
        this.internalToken = internalToken;
        byte[] data = fixturePath.isBlank() ? new byte[0] : Files.readAllBytes(Path.of(fixturePath));
        fixture = data.length == 0 ? Set.of() : loadFixture(data);
        fixtureVersion = data.length == 0 ? null : digest(data);
    }

    boolean enabled() {
        return !fixture.isEmpty() && internalToken.matches("[A-Za-z0-9_-]{32,128}")
                && "http".equals(base.getScheme()) && base.getUserInfo() == null
                && "127.0.0.1".equals(base.getHost());
    }

    String fixtureVersion() {
        return fixtureVersion;
    }

    boolean allows(Profile profile) {
        return fixture.contains(profile);
    }

    Metadata metadata() {
        if (!enabled()) return Metadata.off();
        try {
            JsonNode reply = request("/semantic/status", null);
            if (reply.path("configured").isBoolean() && reply.path("configured").booleanValue()) {
                return new Metadata(true, field(reply, "model", 60), field(reply, "promptVersion", 40));
            }
        } catch (Failure unavailable) {
            // Python/provider outages fail selection closed.
        }
        return Metadata.off();
    }

    Result analyze(Profile profile, String model, String promptVersion) throws Failure {
        if (!enabled() || !allows(profile)) throw new Failure("FIXTURE_NOT_ALLOWED");
        JsonNode reply = request("/semantic/analyze", json.valueToTree(profile));
        String verdict = field(reply, "verdict", 12);
        if (!Set.of("STRONG", "INTEREST", "EXCLUDE", "UNCERTAIN").contains(verdict)
                || !model.equals(field(reply, "model", 60))
                || !promptVersion.equals(field(reply, "promptVersion", 40))) {
            throw new Failure("INVALID_RESPONSE");
        }
        String product = field(reply, "product", 100);
        String signal = field(reply, "signal", 160);
        String purpose = field(reply, "purpose", 100);
        String reason = field(reply, "reason", 300);
        JsonNode evidenceNode = reply.path("evidence");
        if (!evidenceNode.isArray() || evidenceNode.size() > 4
                || ((verdict.equals("STRONG") || verdict.equals("INTEREST")) && evidenceNode.isEmpty())) {
            throw new Failure("INVALID_RESPONSE");
        }
        List<String> evidence = new ArrayList<>();
        for (JsonNode cite : evidenceNode) {
            String phrase = text(cite, 200);
            if (!profile.bio().contains(phrase) || evidence.contains(phrase)) throw new Failure("INVALID_RESPONSE");
            evidence.add(phrase);
        }
        return new Result(verdict, product, signal, purpose, reason, List.copyOf(evidence),
                count(reply.path("inputTokens")), count(reply.path("outputTokens")));
    }

    private Set<Profile> loadFixture(byte[] data) throws IOException {
        JsonNode file = json.readTree(data);
        if (file == null || !file.isArray()) throw new IllegalArgumentException("Invalid semantic fixture array");
        var profiles = new HashSet<Profile>();
        for (JsonNode row : file) {
            JsonNode bio = row.path("bio");
            JsonNode language = row.path("language");
            if (!bio.isTextual() || (!language.isTextual() && !language.isMissingNode() && !language.isNull())) {
                throw new IllegalArgumentException("Invalid semantic fixture row");
            }
            String value = bio.asText().strip();
            String lang = language.asText("").strip();
            lang = lang.isBlank() ? null : lang;
            if (value.isBlank() || value.codePointCount(0, value.length()) > 1000
                    || (lang != null && !lang.matches("[A-Za-z0-9]{1,8}(?:-[A-Za-z0-9]{1,8})*"))
                    || (lang != null && lang.length() > 40)) {
                // Keep the configured fixture usable for local evaluation when a row is ineligible.
                continue;
            }
            profiles.add(new Profile(value, lang));
        }
        return Set.copyOf(profiles);
    }

    private JsonNode request(String path, JsonNode payload) throws Failure {
        var request = HttpRequest.newBuilder(base.resolve(path))
                .header("X-Outreach-Semantic-Token", internalToken)
                .timeout(Duration.ofSeconds(payload == null ? 3 : 25));
        if (payload != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        }
        HttpResponse<String> reply;
        try {
            reply = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new Failure("TIMEOUT");
        } catch (IOException unavailable) {
            throw new Failure("PROVIDER_UNAVAILABLE");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new Failure("INTERRUPTED");
        }
        switch (reply.statusCode()) {
            case 200 -> { }
            case 401, 403 -> throw new Failure("AUTH_FAILED");
            case 402 -> throw new Failure("BALANCE_EXHAUSTED");
            case 413, 415, 422 -> throw new Failure("INPUT_REJECTED");
            case 429 -> throw new Failure("RATE_LIMITED");
            case 502 -> throw new Failure("INVALID_RESPONSE");
            case 504 -> throw new Failure("TIMEOUT");
            default -> throw new Failure("PROVIDER_UNAVAILABLE");
        }
        try {
            var body = json.readTree(reply.body());
            if (body == null || !body.isObject()) throw new Failure("INVALID_RESPONSE");
            return body;
        } catch (IOException invalid) {
            throw new Failure("INVALID_RESPONSE");
        }
    }

    private static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static String field(JsonNode value, String name, int max) throws Failure {
        return text(value.path(name), max);
    }

    private static String text(JsonNode value, int max) throws Failure {
        if (!value.isTextual()) throw new Failure("INVALID_RESPONSE");
        String text = value.textValue();
        if (text.isBlank() || text.codePointCount(0, text.length()) > max) throw new Failure("INVALID_RESPONSE");
        return text;
    }

    private static int count(JsonNode value) throws Failure {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > 1_000_000) {
            throw new Failure("INVALID_RESPONSE");
        }
        return value.intValue();
    }

    record Profile(String bio, String language) {}
    record Metadata(boolean configured, String model, String promptVersion) {
        static Metadata off() {
            return new Metadata(false, null, null);
        }
    }
    record Result(String verdict, String product, String signal, String purpose, String reason,
                  List<String> evidence, int inputTokens, int outputTokens) {}
    static final class Failure extends Exception {
        Failure(String reason) {
            super(reason);
        }
    }
}
