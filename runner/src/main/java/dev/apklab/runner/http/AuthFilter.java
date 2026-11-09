package dev.apklab.runner.http;

import dev.apklab.runner.RunnerSettings;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.context.annotation.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bearer-token authentication of the runner API (RUNNER_TOKEN): "Authorization: Bearer <token>", or "?access_token=<token>" on GET (EventSource cannot set
 * headers). GET /health and GET / are public. RUNNER_AUTH=off disables it (local, single-user use only).
 */
@ServerFilter("/**")
@Context
public class AuthFilter implements Ordered {
    public static final String PRINCIPAL = "apklab.principal";
    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);

    /** Who called: "operator" (the token holder) or "local" (auth off), and the namespace for app data (X-Client-Id). */
    public record Principal(String id, String namespace) {}

    private final RunnerSettings settings;

    public AuthFilter(RunnerSettings settings) {
        this.settings = settings;
        if (!settings.authDisabled() && settings.getToken().isBlank()) {
            throw new IllegalStateException("RUNNER_TOKEN is required (or RUNNER_AUTH=off for local, single-user use)");
        }
        if (settings.authDisabled()) LOG.warn("RUNNER_AUTH=off: anyone who reaches this port can run APKs on this machine; keep it on loopback");
    }

    @Override
    public int getOrder() { return CorsFilter.ORDER + 10; }

    @RequestFilter
    @Nullable
    public HttpResponse<?> authenticate(HttpRequest<?> request) {
        String path = request.getPath();
        if (request.getMethod() == HttpMethod.OPTIONS) return null;
        if (request.getMethod() == HttpMethod.GET && ("/health".equals(path) || "/".equals(path))) return null;
        String namespace = request.getHeaders().get("X-Client-Id");
        namespace = namespace == null || namespace.isBlank() ? "default" : namespace.substring(0, Math.min(128, namespace.length()));
        if (settings.authDisabled()) {
            request.setAttribute(PRINCIPAL, new Principal("local", namespace));
            return null;
        }
        if (!Tokens.matches(Tokens.presented(request), settings.getToken())) {
            return Errors.response(401, "unauthorized", "Missing or invalid bearer token", null);
        }
        request.setAttribute(PRINCIPAL, new Principal("operator", namespace));
        return null;
    }

    public static Principal principal(HttpRequest<?> request) {
        return request.getAttribute(PRINCIPAL, Principal.class).orElse(new Principal("local", "default"));
    }
}
