package dev.apklab.queue.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.apklab.api.ApiException;
import dev.apklab.api.SessionConfig;
import dev.apklab.queue.QueueSettings;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/** Parsing of job / lease submissions: the spec JSON (strict), Bean Validation, the APK from the body, apkBase64 or apkUrl (allow-listed hosts only). */
@Singleton
public class Submissions {
    private final ObjectMapper strict;
    private final Validator validator;
    private final QueueSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public Submissions(ObjectMapper mapper, Validator validator, QueueSettings settings) {
        this.strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.validator = validator;
        this.settings = settings;
    }

    public <T> T parse(@Nullable String text, Class<T> type, String what) {
        String json = text == null || text.isBlank() ? "{}" : text.trim();
        if (!json.startsWith("{")) {
            try { json = new String(Base64.getUrlDecoder().decode(json), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { throw ApiException.badRequest(what + " must be JSON (or base64url JSON)"); }
        }
        T value;
        try {
            value = strict.readValue(json, type);
        } catch (UnrecognizedPropertyException e) {
            String path = e.getPath().stream().map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]").collect(Collectors.joining(".")).replace(".[", "[");
            throw ApiException.badRequest(what + ": unknown key \"" + e.getPropertyName() + "\" at " + path);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest(what + " is not valid: " + e.getOriginalMessage());
        }
        return validate(value, what);
    }

    public <T> T validate(T value, String what) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw ApiException.badRequest(violations.stream().sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> what + "." + v.getPropertyPath() + ": " + v.getMessage()).collect(Collectors.joining("; ")));
        }
        return value;
    }

    public JsonNode config(@Nullable SessionConfig config) {
        if (config == null) return strict.createObjectNode();
        JsonNode node = strict.valueToTree(config);
        if (node.toString().length() > 8 * 1024 * 1024) throw ApiException.badRequest("config larger than 8 MiB");
        return node;
    }

    public byte[] apk(@Nullable byte[] body, @Nullable String apkBase64, @Nullable String apkUrl) {
        if (body != null && body.length > 0) return body;
        if (apkBase64 != null) {
            try { return Base64.getMimeDecoder().decode(apkBase64); } catch (IllegalArgumentException e) { throw ApiException.badRequest("apkBase64 is not base64"); }
        }
        if (apkUrl != null) return fetch(apkUrl);
        throw ApiException.badRequest("No APK: send it as a file, apkBase64 or apkUrl");
    }

    /** apkUrl: https (http only for listed hosts), host in QUEUE_APK_URL_HOSTS, no redirects, size-bounded. */
    byte[] fetch(String text) {
        URI target;
        try { target = URI.create(text); } catch (IllegalArgumentException e) { throw ApiException.badRequest("apkUrl is not a URL"); }
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase();
        String hostPort = target.getPort() > 0 ? host + ":" + target.getPort() : host;
        var allowed = settings.apkUrlHostList();
        boolean listed = allowed.contains("*") || allowed.contains(host) || allowed.contains(hostPort);
        if (!"https".equals(target.getScheme()) && !("http".equals(target.getScheme()) && (allowed.contains(host) || allowed.contains(hostPort)))) {
            throw ApiException.badRequest("apkUrl must be https (http only for hosts listed in QUEUE_APK_URL_HOSTS)");
        }
        if (!listed) throw ApiException.forbidden("apk-url-not-allowed", "apkUrl host " + hostPort + " is not in QUEUE_APK_URL_HOSTS");
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw new ApiException(502, "apk-fetch-failed", "apkUrl answered " + response.statusCode());
            try (InputStream in = response.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > settings.maxApkBytes()) throw new ApiException(413, "too-large", "apkUrl content larger than QUEUE_MAX_APK_MB");
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new ApiException(502, "apk-fetch-failed", "Could not fetch apkUrl: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", "interrupted");
        }
    }
}
