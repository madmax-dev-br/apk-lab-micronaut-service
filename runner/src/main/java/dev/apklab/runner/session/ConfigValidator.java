package dev.apklab.runner.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import dev.apklab.api.ApiException;
import dev.apklab.api.SessionConfig;
import dev.apklab.runner.RunnerSettings;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * First check of a session config, before any sidecar starts: JSON shape (unknown keys are errors), Bean Validation of the {@link SessionConfig} record and the
 * runner's own policy (plugins disabled by default, memory ceiling). The values themselves are validated again by the lab's sanitizers in the sidecar.
 */
@Singleton
public class ConfigValidator {
    private final ObjectMapper strict;
    private final Validator validator;
    private final RunnerSettings settings;

    public ConfigValidator(ObjectMapper mapper, Validator validator, RunnerSettings settings) {
        this.strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.validator = validator;
        this.settings = settings;
    }

    /** Parses and checks a config given as JSON text (multipart part, X-APK-Config header). Base64url JSON is accepted for the header. */
    public JsonNode parse(@Nullable String text) {
        if (text == null || text.isBlank()) return strict.createObjectNode();
        String json = text.trim();
        if (!json.startsWith("{")) {
            try { json = new String(java.util.Base64.getUrlDecoder().decode(json), java.nio.charset.StandardCharsets.UTF_8); }
            catch (IllegalArgumentException e) { throw ApiException.badRequest("config must be JSON (or base64url JSON)"); }
        }
        SessionConfig config;
        try {
            config = strict.readValue(json, SessionConfig.class);
        } catch (UnrecognizedPropertyException e) {
            throw ApiException.badRequest("config: unknown key \"" + e.getPropertyName() + "\" at " + pathOf(e));
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("config is not valid: " + e.getOriginalMessage());
        }
        return check(config, "config");
    }

    /** Checks an already bound config (JSON bodies). */
    public JsonNode check(@Nullable SessionConfig config, String where) {
        if (config == null) return strict.createObjectNode();
        Set<ConstraintViolation<SessionConfig>> violations = validator.validate(config);
        if (!violations.isEmpty()) {
            String message = violations.stream().sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> where + "." + v.getPropertyPath() + ": " + v.getMessage()).collect(Collectors.joining("; "));
            throw ApiException.badRequest(message);
        }
        if (config.plugins() != null && !config.plugins().isEmpty() && !settings.isAllowPlugins()) {
            throw ApiException.forbidden("plugins-disabled", "Plugins are disabled on this runner (the operator must set RUNNER_ALLOW_PLUGINS=true; plugin code runs on the server, see the security model in the documentation)");
        }
        if (config.plugins() != null && config.plugins().size() > settings.getPluginMax()) {
            throw ApiException.badRequest(where + ".plugins: at most " + settings.getPluginMax() + " plugins (RUNNER_PLUGIN_MAX)");
        }
        if (config.memoryMiB() != null && config.memoryMiB() > settings.getMaxMemoryMib()) {
            throw ApiException.forbidden("memory-policy", where + ".memoryMiB above the runner's limit (" + settings.getMaxMemoryMib() + " MiB, RUNNER_MAX_MEMORY_MIB)");
        }
        if (config.rasterScale() != null && !SessionConfig.RASTER_SCALE_STEPS.contains(config.rasterScale())) {
            throw ApiException.badRequest(where + ".rasterScale: one of 1, 0.75, 0.5, 0.35, 0.25 (default 0.5; used while the screen moves when raster is \"progressive\")");
        }
        if (config.launch() != null && !(config.launch().isBoolean() || (config.launch().isTextual() && config.launch().asText().length() <= 500))) {
            throw ApiException.badRequest(where + ".launch: true, false or an Activity name");
        }
        return strict.valueToTree(config);
    }

    private static String pathOf(UnrecognizedPropertyException e) {
        return e.getPath().stream().map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]").collect(Collectors.joining(".")).replace(".[", "[");
    }
}
