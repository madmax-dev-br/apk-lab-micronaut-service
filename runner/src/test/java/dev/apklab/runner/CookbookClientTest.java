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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recipes of the documentation, as published (Micronaut HttpClient): 1 (a session created with an endpoint mock, the mock in the network log), 16 (recreate keeps the
 * ViewModel, reopen creates a new one) and 17 (wait for "Tela pronta" and take the screenshot).
 */
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
                "runner.max-line-mb", "64", "runner.operation-timeout-seconds", "45", "runner.call-margin-seconds", "15", "runner.app-data-dir", "off");   // the "test" environment shrinks these for the fake sidecar
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

    private String criar(BlockingHttpClient http, String token, byte[] apk, String config) {
        MultipartBody body = MultipartBody.builder()
                .addPart("apk", "app.apk", MediaType.of("application/vnd.android.package-archive"), apk)
                .addPart("config", config)
                .build();
        JsonNode created = http.retrieve(HttpRequest.POST("/sessions", body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE).bearerAuth(token), JsonNode.class);
        return created.path("session").path("id").asText();
    }

    private static String appViewModel(BlockingHttpClient http, String token, String id) {
        for (JsonNode vm : http.retrieve(HttpRequest.GET("/sessions/" + id + "/view-models").bearerAuth(token), JsonNode.class))
            if (!vm.path("library").asBoolean()) return vm.path("handle").asText();
        return null;
    }

    @Test
    void recipeSixteenRecreateAndReopen() throws Exception {
        String token = "cookbook-token";
        byte[] apk = Files.readAllBytes(Path.of(System.getenv("APK_LAB_TEST_APK_DIR")).resolve("compose-approvals-debug.apk"));
        try (HttpClient client = HttpClient.create(server.getURL())) {
            BlockingHttpClient http = client.toBlocking();
            String id = criar(http, token, apk, "{\"launch\": true}");
            http.retrieve(HttpRequest.POST("/sessions/" + id + "/wait", Map.of("ready", true)).bearerAuth(token), JsonNode.class);
            // ---- begin: code of the cookbook (recipe 16) ----
            String antes = appViewModel(http, token, id);
            http.retrieve(HttpRequest.POST("/sessions/" + id + "/reload", Map.of("mode", "recreate")).bearerAuth(token), JsonNode.class);
            boolean mantido = antes.equals(appViewModel(http, token, id));
            http.retrieve(HttpRequest.PATCH("/sessions/" + id + "/config", Map.of("endpointMocks", List.of(Map.of("method", "GET", "url", "/v1/pedidos", "body", "[]")))).bearerAuth(token), JsonNode.class);
            http.retrieve(HttpRequest.POST("/sessions/" + id + "/reload", Map.of("mode", "reopen")).bearerAuth(token), JsonNode.class);
            boolean outro = !antes.equals(appViewModel(http, token, id));
            JsonNode pronta = http.retrieve(HttpRequest.POST("/sessions/" + id + "/wait", Map.of("ready", true)).bearerAuth(token), JsonNode.class);
            // ---- end ----
            assertTrue(mantido, "recreate keeps the retained ViewModel");
            assertTrue(outro, "reopen creates a new one");
            assertTrue(pronta.path("ready").asBoolean());
        }
    }

    @Test
    void recipeSeventeenWaitForTheReadyScreenAndTakeTheScreenshot() throws Exception {
        String token = "cookbook-token";
        byte[] apk = Files.readAllBytes(Path.of(System.getenv("APK_LAB_TEST_APK_DIR")).resolve("net-probe.apk"));
        // the documented recipe mocks /v1/perfil; the probe APK asks for /net-probe/hello.json
        try (HttpClient client = HttpClient.create(server.getURL())) {
            BlockingHttpClient http = client.toBlocking();
            // ---- begin: code of the cookbook (recipe 17) ----
            String id = criar(http, token, apk, """
                {"endpointMocks": [{"method": "GET", "url": "/net-probe/hello.json", "status": 200,
                                    "headers": [{"name": "Content-Type", "value": "application/json"}], "body": "{\\"nome\\": \\"Ana\\"}"}]}""");
            http.retrieve(HttpRequest.POST("/sessions/" + id + "/launch", Map.of()).bearerAuth(token), JsonNode.class);
            JsonNode pronta = http.retrieve(HttpRequest.POST("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 15000)).bearerAuth(token), JsonNode.class);
            System.out.println("pronta em " + pronta.path("waitedMs").asLong() + " ms");
            byte[] png = http.retrieve(HttpRequest.GET("/sessions/" + id + "/screenshot.png?waitReady=1").bearerAuth(token), byte[].class);
            // ---- end ----
            assertTrue(pronta.path("ready").asBoolean(), pronta.toString());
            assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(png, 4));
            JsonNode ready = http.retrieve(HttpRequest.GET("/sessions/" + id + "/ready").bearerAuth(token), JsonNode.class);
            assertTrue(ready.path("ready").asBoolean() && ready.path("current").asBoolean(), ready.toString());
            http.retrieve(HttpRequest.POST("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "Load"))).bearerAuth(token), JsonNode.class);
            JsonNode both = http.retrieve(HttpRequest.POST("/sessions/" + id + "/wait", Map.of("ready", true, "selector", Map.of("textContains", "Ana"), "timeoutMs", 15000)).bearerAuth(token), JsonNode.class);
            assertTrue(both.path("ready").asBoolean() && both.path("element").isObject(), both.toString());
        }
    }
}
