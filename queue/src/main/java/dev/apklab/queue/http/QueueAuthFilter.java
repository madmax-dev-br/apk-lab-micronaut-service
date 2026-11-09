package dev.apklab.queue.http;

import dev.apklab.queue.QueueSettings;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.Map;

/**
 * Client authentication of the queue (QUEUE_CLIENTS="alice:token1:3,bob:token2"): each token is one client, who sees only its own jobs and leases.
 * GET /health and GET / are public. QUEUE_AUTH=off: every caller is the client of its X-Client-Id header (or "local").
 */
@ServerFilter("/**")
public class QueueAuthFilter implements Ordered {
    public static final String CLIENT = "apklab.client";
    private final QueueSettings settings;
    private final Map<String, QueueSettings.Client> clients;

    public QueueAuthFilter(QueueSettings settings) {
        this.settings = settings;
        this.clients = settings.clientMap();
    }

    @Override
    public int getOrder() { return CorsFilter.ORDER + 10; }

    @RequestFilter
    @Nullable
    public HttpResponse<?> authenticate(HttpRequest<?> request) {
        String path = request.getPath();
        if (request.getMethod() == HttpMethod.OPTIONS) return null;
        if (request.getMethod() == HttpMethod.GET && ("/health".equals(path) || "/".equals(path))) return null;
        if (settings.authDisabled()) {
            String id = request.getHeaders().get("X-Client-Id");
            id = id == null ? "local" : id.replaceAll("[^\\w.-]", "");
            request.setAttribute(CLIENT, new QueueSettings.Client(id.isEmpty() ? "local" : id.substring(0, Math.min(64, id.length())), "", null));
            return null;
        }
        String token = Tokens.presented(request);
        for (Map.Entry<String, QueueSettings.Client> entry : clients.entrySet()) {
            if (Tokens.matches(token, entry.getKey())) {
                request.setAttribute(CLIENT, entry.getValue());
                return null;
            }
        }
        return Errors.response(401, "unauthorized", "Missing or invalid bearer token", null);
    }

    public static QueueSettings.Client client(HttpRequest<?> request) {
        return request.getAttribute(CLIENT, QueueSettings.Client.class).orElseThrow(() -> new IllegalStateException("no client"));
    }
}
