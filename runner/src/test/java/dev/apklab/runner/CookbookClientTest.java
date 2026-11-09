package dev.apklab.runner;

import com.fasterxml.jackson.databind.JsonNode;
import dev.apklab.runner.session.SessionManager;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Recipe 1 of the documentation, exactly as published (Micronaut HttpClient): a session created with an endpoint mock, launched, the mock in the network log. */
@MicronautTest(environments = "e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "APK_LAB_RUNTIME_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "APK_LAB_TEST_APK_DIR", matches = ".+")
class CookbookClientTest implements TestPropertyProvider {
    @Inject EmbeddedServer server;
    @Inject SessionManager sessions;

    @Override
    public Map<String, String> getProperties() {
        return Map.of("runner.runtime-dir", System.getenv("APK_LAB_RUNTIME_DIR"), "runner.sidecar-script", "../sidecar/vm-host.mjs", "runner.token", "cookbook-token",
                "runner.max-line-mb", "64", "runner.operation-timeout-seconds", "45", "runner.call-margin-seconds", "15");   // the "test" environment shrinks these for the fake sidecar
    }

    @AfterEach
    void closeAll() { sessions.list().forEach(s -> sessions.close(s, "test")); }

    @Test
    void recipeOneWithMicronautHttpClient() throws Exception {
        String token = "cookbook-token";
        byte[] apk = Files.readAllBytes(Path.of(System.getenv("APK_LAB_TEST_APK_DIR")).resolve("net-probe.apk"));
        // the documented recipe uses https://api.exemplo.com/v1/perfil; the probe APK asks for /net-probe/hello.json on its default origin
        String config = """
                {"endpointMocks": [{"name": "perfil", "method": "GET", "url": "/net-probe/hello.json", "status": 200,
                                    "headers": [{"name": "Content-Type", "value": "application/json"}],
                                    "body": "{\\"nome\\": \\"Ana\\", \\"plano\\": \\"premium\\"}"}],
                 "launch": true}""";

        // ---- begin: code of the cookbook (recipe 1) ----
        try (HttpClient client = HttpClient.create(server.getURL())) {
            BlockingHttpClient http = client.toBlocking();
            MultipartBody body = MultipartBody.builder()
                    .addPart("apk", "app.apk", MediaType.of("application/vnd.android.package-archive"), apk)
                    .addPart("config", config)
                    .build();
            JsonNode created = http.retrieve(HttpRequest.POST("/sessions", body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token), JsonNode.class);
            String id = created.path("session").path("id").asText();

            http.retrieve(HttpRequest.POST("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "Load"))).bearerAuth(token), JsonNode.class);
            http.retrieve(HttpRequest.POST("/sessions/" + id + "/wait", Map.of("selector", Map.of("textContains", "HTTP 200"), "timeoutMs", 15000)).bearerAuth(token), JsonNode.class);

            JsonNode network = http.retrieve(HttpRequest.GET("/sessions/" + id + "/network").bearerAuth(token), JsonNode.class);
            JsonNode row = network.path("requests").get(0);
            System.out.printf("%s %s -> %d (mock: %s)%n", row.path("method").asText(), row.path("url").asText(), row.path("status").asInt(), row.path("mockLabel").asText());
            // ---- end ----
            assertEquals(200, row.path("status").asInt());
            assertEquals("perfil", row.path("mockLabel").asText());
            assertFalse(row.path("mockId").isNull());
        }
    }
}
