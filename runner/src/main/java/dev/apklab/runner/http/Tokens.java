package dev.apklab.runner.http;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bearer tokens: where a request carries one, and a constant-time comparison. */
public final class Tokens {
    private Tokens() {}

    /** Authorization: Bearer <t>, or ?access_token=<t> on GET/HEAD (EventSource cannot set headers). */
    public static @Nullable String presented(HttpRequest<?> request) {
        String header = request.getHeaders().getAuthorization().orElse("");
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) return header.substring(7).trim();
        if (request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.HEAD) return request.getParameters().get("access_token");
        return null;
    }

    public static boolean matches(@Nullable String presented, String expected) {
        if (presented == null || expected == null || expected.isEmpty()) return false;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] a = sha.digest(presented.getBytes(StandardCharsets.UTF_8));
            byte[] b = MessageDigest.getInstance("SHA-256").digest(expected.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(a, b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
