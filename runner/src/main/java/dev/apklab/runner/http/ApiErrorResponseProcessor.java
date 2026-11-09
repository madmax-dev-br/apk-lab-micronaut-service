package dev.apklab.runner.http;

import dev.apklab.api.ApiError;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.server.exceptions.response.Error;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.JsonErrorResponseBodyProvider;
import jakarta.inject.Singleton;

import java.util.stream.Collectors;

/** Micronaut's own errors (unknown route, bad JSON, unknown body key, wrong content type, body too large) in the API's error shape. */
@Singleton
@Replaces(JsonErrorResponseBodyProvider.class)
public class ApiErrorResponseProcessor implements JsonErrorResponseBodyProvider<ApiError> {
    @Override
    public ApiError body(ErrorContext context, HttpResponse<?> response) {
        int status = response.code();
        String message = context.getErrors().stream().map(Error::getMessage).filter(m -> m != null && !m.isBlank()).collect(Collectors.joining("; "));
        if (message.isBlank()) message = context.getRootCause().map(Throwable::getMessage).orElse(response.reason());
        return ApiError.of(code(status), message, null);
    }

    public static String code(int status) {
        return switch (status) {
            case 400 -> "bad-request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 413 -> "too-large";
            case 415 -> "unsupported-media-type";
            default -> "http-" + status;
        };
    }
}
