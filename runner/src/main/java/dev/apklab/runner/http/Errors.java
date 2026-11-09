package dev.apklab.runner.http;

import dev.apklab.api.ApiError;
import dev.apklab.api.ApiException;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.stream.Collectors;

/** Every error of the API as { "error": { "code", "message", "details"? } } (the shape of the Node service). */
public final class Errors {
    private static final Logger LOG = LoggerFactory.getLogger(Errors.class);
    private Errors() {}

    public static MutableHttpResponse<ApiError> response(int status, String code, String message, @Nullable Object details) {
        return HttpResponse.<ApiError>status(HttpStatus.valueOf(status)).body(ApiError.of(code, message, details));
    }

    @Produces
    @Singleton
    public static class ApiExceptionHandler implements ExceptionHandler<ApiException, HttpResponse<?>> {
        @Override
        public HttpResponse<?> handle(HttpRequest request, ApiException e) {
            MutableHttpResponse<ApiError> response = response(e.status(), e.code(), e.getMessage(), e.details());
            if (e.retryAfterSeconds() != null) response.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
            return response;
        }
    }

    @Produces
    @Singleton
    @Replaces(io.micronaut.validation.exceptions.ConstraintExceptionHandler.class)
    @Requires(classes = ConstraintViolationException.class)
    public static class ValidationHandler implements ExceptionHandler<ConstraintViolationException, HttpResponse<?>> {
        @Override
        public HttpResponse<?> handle(HttpRequest request, ConstraintViolationException e) {
            String message = e.getConstraintViolations().stream().sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> cleanPath(v.getPropertyPath().toString()) + ": " + v.getMessage()).collect(Collectors.joining("; "));
            return response(400, "bad-request", message, null);
        }
        private static String cleanPath(String path) {
            // "tap.body.selector.text" -> "selector.text"
            String[] parts = path.split("\\.", 3);
            return parts.length == 3 && ("body".equals(parts[1]) || parts[1].startsWith("body")) ? parts[2] : path;
        }
    }

    @Produces
    @Singleton
    public static class FallbackHandler implements ExceptionHandler<RuntimeException, HttpResponse<?>> {
        @Override
        public HttpResponse<?> handle(HttpRequest request, RuntimeException e) {
            if (e instanceof dev.apklab.runner.sidecar.SidecarException se) return response(se.status(), se.code(), se.getMessage(), se.details());
            LOG.error("{} {} failed", request.getMethod(), request.getPath(), e);
            return response(500, "internal", "Internal error", null);
        }
    }
}
