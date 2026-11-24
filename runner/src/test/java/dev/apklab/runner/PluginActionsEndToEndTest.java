package dev.apklab.runner;

import com.fasterxml.jackson.databind.JsonNode;
import dev.apklab.runner.session.SessionManager;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The actions of a plugin sent through the API (lab.invoke, lab.mock, lab.scrollToPercent, lab.reload) on the synthetic Compose example. Runs with APK_LAB_RUNTIME_DIR and APK_LAB_TEST_APK_DIR. */
@MicronautTest(environments = "e2e", startApplication = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "APK_LAB_RUNTIME_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "APK_LAB_TEST_APK_DIR", matches = ".+")
class PluginActionsEndToEndTest implements TestPropertyProvider {
    static final String TOKEN = "e2e-token";
    static final String PLUGIN = """
            const out = {};
            const run = async (key, fn) => { try { out[key] = await fn(); } catch (error) { out[key] = 'error ' + error.code; } };
            const tela = await lab.activity();
            await run('pacote', async () => (await lab.invoke(tela.handle, 'getPackageName', [], { signature: '()Ljava/lang/String;' })).value);
            await run('abs', async () => (await lab.invoke({ static: 'java.lang.Math' }, 'abs', [-5], { signature: '(I)I' })).value);
            await run('excecao', () => lab.invoke({ static: 'java.lang.Integer' }, 'parseInt', ['x'], { signature: '(Ljava/lang/String;)I' }));
            await run('semMetodo', () => lab.invoke(tela.handle, 'naoExiste'));
            const mock = await lab.mock({ method: 'GET', url: 'https://api.example.com/v1/x', body: { a: 1 } });
            await run('mocks', async () => (await lab.mocks()).length);
            await run('removido', async () => (await lab.unmock(mock.id)).removed);
            await run('rolagem', async () => (await lab.scrollToPercent(100)).percentY);
            await run('recarregar', async () => (await lab.reload('recreate')).mode);
            lab.ui.kv(out, { id: 'saida' });
            """;
    @Inject EmbeddedServer server;
    @Inject SessionManager sessions;

    @Override
    public Map<String, String> getProperties() {
        return Map.ofEntries(Map.entry("runner.runtime-dir", System.getenv("APK_LAB_RUNTIME_DIR")), Map.entry("runner.sidecar-script", "../sidecar/vm-host.mjs"), Map.entry("runner.token", TOKEN),
                Map.entry("runner.max-line-mb", "64"), Map.entry("runner.operation-timeout-seconds", "45"), Map.entry("runner.call-margin-seconds", "15"), Map.entry("runner.max-sessions", "2"),
                Map.entry("runner.allow-plugins", "true"), Map.entry("runner.app-data-dir", "off"));
    }

    @AfterEach
    void closeAll() { sessions.list().forEach(s -> sessions.close(s, "test")); }

    @Test
    void aPluginCallsTheAppsMethodsAndActsOnTheSession() throws Exception {
        TestHttp http = new TestHttp(server.getURL().toString(), TOKEN);
        TestHttp.Response created = http.createSessionMultipart(Files.readAllBytes(Path.of(System.getenv("APK_LAB_TEST_APK_DIR")).resolve("compose-approvals-debug.apk")), "{\"launch\":true}");
        assertEquals(201, created.status(), () -> String.valueOf(created.json()));
        String id = created.json().path("session").path("id").asText();
        assertTrue(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 30000)).status() < 300);
        assertTrue(http.put("/sessions/" + id + "/plugins", Map.of("plugins", List.of(Map.of("id", "acoes", "name", "Acoes", "code", PLUGIN)))).status() < 300);
        JsonNode plugin = null;
        for (int i = 0; i < 300; i++) {
            plugin = http.get("/sessions/" + id + "/plugins/acoes").json();
            if (!plugin.path("blocks").isEmpty() || plugin.path("errorCount").asInt() > 0) break;
            Thread.sleep(100);
        }
        assertEquals(0, plugin.path("errorCount").asInt(), plugin::toString);
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (JsonNode entry : plugin.path("blocks").get(0).path("entries")) out.put(entry.get(0).asText(), entry.get(1).asText());
        assertEquals(Map.of("pacote", "dev.apkweb.approvals", "abs", "5", "excecao", "error exception", "semMetodo", "error no-method", "mocks", "1", "removido", "true", "rolagem", "100", "recarregar", "recreate"), out);
        JsonNode session = http.get("/sessions/" + id).json();
        assertEquals("running", (session.has("session") ? session.path("session") : session).path("state").asText());
    }
}
