package dev.apklab.runner;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CORS of the runner's API: only the configured origins, preflights answered before authentication, credentials never needed (bearer tokens). */
@MicronautTest(environments = "test")
@Property(name = "runner.cors-origins", value = "https://painel.exemplo.test, http://localhost:5173")
class CorsTest {
    @Inject EmbeddedServer server;

    private TestHttp.Response preflight(String origin, String path) {
        return new TestHttp(server.getURL().toString(), null).send("OPTIONS", path, null, null,
                Map.of("Origin", origin, "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization, content-type"));
    }

    @Test
    void anAllowedOriginGetsPreflightAndResponseHeaders() {
        TestHttp.Response pre = preflight("https://painel.exemplo.test", "/sessions");
        assertEquals(204, pre.status());
        assertEquals("https://painel.exemplo.test", pre.header("access-control-allow-origin"));
        assertTrue(pre.header("access-control-allow-headers").contains("authorization"));
        assertTrue(pre.header("access-control-allow-methods").contains("PATCH"));
        assertNull(pre.header("access-control-allow-credentials"), "bearer tokens, no cookies: credentials are not needed");

        TestHttp.Response health = new TestHttp(server.getURL().toString(), null).send("GET", "/health", null, null, Map.of("Origin", "http://localhost:5173"));
        assertEquals(200, health.status());
        assertEquals("http://localhost:5173", health.header("access-control-allow-origin"));
        assertTrue(health.header("access-control-expose-headers").contains("x-image-size"));

        TestHttp.Response unauthorized = new TestHttp(server.getURL().toString(), null).send("GET", "/sessions", null, null, Map.of("Origin", "http://localhost:5173"));
        assertEquals(401, unauthorized.status());
        assertEquals("http://localhost:5173", unauthorized.header("access-control-allow-origin"), "errors carry CORS headers too, so the browser can read them");
    }

    @Test
    void otherOriginsAreRefused() {
        TestHttp.Response pre = preflight("https://outro.exemplo.test", "/sessions");
        assertEquals(403, pre.status());
        assertEquals("cors-origin-not-allowed", pre.code());
        assertNull(pre.header("access-control-allow-origin"));
        TestHttp.Response health = new TestHttp(server.getURL().toString(), null).send("GET", "/health", null, null, Map.of("Origin", "https://outro.exemplo.test"));
        assertNull(health.header("access-control-allow-origin"));
    }
}
