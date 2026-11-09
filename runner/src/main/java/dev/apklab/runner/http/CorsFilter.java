package dev.apklab.runner.http;

import dev.apklab.runner.RunnerSettings;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.List;

/**
 * CORS for the runner's own API, so a browser front end on another origin can call it. Off by default (RUNNER_CORS_ORIGINS empty: no CORS headers, preflights
 * refused). The VM itself needs no CORS on the server: the app's HTTP requests leave from the sidecar process, not from a browser.
 */
@ServerFilter("/**")
public class CorsFilter implements Ordered {
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;
    public static final String ALLOW_HEADERS = "authorization, content-type, x-apk-config, x-client-id, last-event-id";
    public static final String EXPOSE_HEADERS = "x-request-id, retry-after, content-disposition, x-image-size, x-screenshot-notes";
    public static final String ALLOW_METHODS = "GET, POST, PUT, PATCH, DELETE, OPTIONS";

    private final List<String> origins;

    @jakarta.inject.Inject
    public CorsFilter(RunnerSettings settings) { this(settings.corsOriginList()); }

    public CorsFilter(List<String> origins) { this.origins = List.copyOf(origins); }

    @Override
    public int getOrder() { return ORDER; }

    public boolean allowed(@Nullable String origin) {
        return origin != null && !origins.isEmpty() && (origins.contains("*") || origins.contains(origin));
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> preflight(HttpRequest<?> request) {
        if (request.getMethod() != HttpMethod.OPTIONS) return null;
        String origin = request.getHeaders().getOrigin().orElse(null);
        if (origin == null) return null;
        if (!allowed(origin)) return Errors.response(403, "cors-origin-not-allowed", "CORS origin not allowed by RUNNER_CORS_ORIGINS: " + origin, null);
        return HttpResponse.noContent()
                .header("Access-Control-Allow-Origin", origins.contains("*") ? "*" : origin)
                .header("Vary", "Origin")
                .header("Access-Control-Allow-Methods", ALLOW_METHODS)
                .header("Access-Control-Allow-Headers", ALLOW_HEADERS)
                .header("Access-Control-Max-Age", "600");
    }

    @ResponseFilter
    public void headers(HttpRequest<?> request, MutableHttpResponse<?> response) {
        String origin = request.getHeaders().getOrigin().orElse(null);
        if (request.getMethod() == HttpMethod.OPTIONS || !allowed(origin)) return;
        response.header("Access-Control-Allow-Origin", origins.contains("*") ? "*" : origin);
        response.header("Vary", "Origin");
        response.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
    }
}
