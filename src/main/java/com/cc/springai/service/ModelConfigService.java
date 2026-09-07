package com.cc.springai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.agent.ModelRuntimeOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConfigService {

    private static final String DEFAULT_ID = "gpt";
    private static final Set<String> BUILT_IN_IDS = Set.of("gpt", "deepseek");
    private static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Pattern MODEL_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final TypeReference<Map<String, Object>> EXTRA_BODY_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final OpenAiChatModel defaultModel;
    private final ObjectMapper objectMapper;
    private final Map<String, OpenAiChatModel> modelCache = new ConcurrentHashMap<>();


    @Value("${app.default-models.model1.base-url:https://api.deepseek.com}")
    private String model1BaseUrl;

    @Value("${app.default-models.model1.completions-path:/v1/chat/completions}")
    private String model1CompletionsPath;

    @Value("${app.default-models.model1.api-key:}")
    private String model1ApiKey;

    @Value("${app.default-models.model1.options.model:deepseek-v4-flash}")
    private String model1Name;

    @Value("${app.default-models.model2.base-url:https://api.caichen.online}")
    private String model2BaseUrl;

    @Value("${app.default-models.model2.completions-path:/v1/chat/completions}")
    private String model2CompletionsPath;

    @Value("${app.default-models.model2.api-key:}")
    private String model2ApiKey;

    @Value("${app.default-models.model2.options.model:gpt-5.5}")
    private String model2Name;

    @Value("${app.default-models.model2.options.temperature:0.4}")
    private Double model2Temperature;

    public ModelConfigService(JdbcTemplate jdbcTemplate, OpenAiChatModel defaultModel, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaultModel = defaultModel;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_model_configs (
                    id VARCHAR(128) PRIMARY KEY,
                    name TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    completions_path TEXT NOT NULL,
                    api_key TEXT,
                    model TEXT NOT NULL,
                    temperature DOUBLE PRECISION,
                    reasoning_effort TEXT,
                    thinking_type TEXT,
                    extra_body TEXT,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """);
        addColumnIfMissing("reasoning_effort", "TEXT");
        addColumnIfMissing("thinking_type", "TEXT");
        addColumnIfMissing("extra_body", "TEXT");
        ensureDefaultConfigs();
    }

    public List<ModelConfigView> list() {
        return jdbcTemplate.query("""
                        SELECT id, name, provider, base_url, completions_path, api_key, model,
                               temperature, reasoning_effort, thinking_type, extra_body, enabled, created_at, updated_at
                        FROM app_model_configs
                        ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, updated_at DESC
                        """,
                (rs, rowNum) -> new ModelConfigView(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("provider"),
                        rs.getString("base_url"),
                        rs.getString("completions_path"),
                        maskApiKey(rs.getString("api_key")),
                        hasText(rs.getString("api_key")),
                        rs.getString("model"),
                        rs.getObject("temperature", Double.class),
                        rs.getString("reasoning_effort"),
                        rs.getString("thinking_type"),
                        rs.getString("extra_body"),
                        rs.getBoolean("enabled"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")),
                DEFAULT_ID);
    }

    public List<ModelConfigExportView> exportAll() {
        return jdbcTemplate.query("""
                        SELECT id, name, provider, base_url, completions_path, api_key, model,
                               temperature, reasoning_effort, thinking_type, extra_body, enabled, created_at, updated_at
                        FROM app_model_configs
                        ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, updated_at DESC
                        """,
                (rs, rowNum) -> {
                    ResolvedModelConfig config = mapConfig(rs);
                    return new ModelConfigExportView(
                            config.id(),
                            config.name(),
                            config.provider(),
                            config.baseUrl(),
                            config.completionsPath(),
                            config.apiKey(),
                            config.model(),
                            config.temperature(),
                            config.reasoningEffort(),
                            config.thinkingType(),
                            config.extraBody(),
                            config.enabled(),
                            config.createdAt(),
                            config.updatedAt());
                },
                DEFAULT_ID);
    }

    public List<ModelConfigView> importAll(List<ModelConfigRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream()
                .filter(Objects::nonNull)
                .map(this::save)
                .toList();
    }

    public ModelConfigView save(ModelConfigRequest request) {
        String id = normalizeId(request.id());
        boolean creating = id.isBlank();
        if (creating) {
            id = UUID.randomUUID().toString();
        }
        validateModelId(id);
        ResolvedModelConfig current = find(id);
        long now = System.currentTimeMillis();
        long createdAt = current == null ? now : current.createdAt();
        String apiKey = hasText(request.apiKey())
                ? request.apiKey().strip()
                : current == null ? "" : current.apiKey();

        ResolvedModelConfig config = new ResolvedModelConfig(
                id,
                required(request.name(), "name"),
                defaultText(request.provider(), "OpenAI Compatible"),
                trimTrailingSlash(required(request.baseUrl(), "baseUrl")),
                normalizeCompletionsPath(defaultText(request.completionsPath(), DEFAULT_COMPLETIONS_PATH)),
                apiKey,
                required(request.model(), "model"),
                request.temperature(),
                normalizeReasoningEffort(request.reasoningEffort()),
                normalizeThinkingType(request.thinkingType()),
                normalizeExtraBody(request.extraBody()),
                request.enabled() == null || request.enabled(),
                createdAt,
                now);
        jdbcTemplate.update("""
                INSERT INTO app_model_configs (
                    id, name, provider, base_url, completions_path, api_key, model,
                    temperature, reasoning_effort, thinking_type, extra_body, enabled, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    completions_path = EXCLUDED.completions_path,
                    api_key = EXCLUDED.api_key,
                    model = EXCLUDED.model,
                    temperature = EXCLUDED.temperature,
                    reasoning_effort = EXCLUDED.reasoning_effort,
                    thinking_type = EXCLUDED.thinking_type,
                    extra_body = EXCLUDED.extra_body,
                    enabled = EXCLUDED.enabled,
                    updated_at = EXCLUDED.updated_at
                """,
                config.id(), config.name(), config.provider(), config.baseUrl(), config.completionsPath(),
                config.apiKey(), config.model(), config.temperature(), config.reasoningEffort(),
                config.thinkingType(), config.extraBody(), config.enabled(), config.createdAt(),
                config.updatedAt());
        modelCache.clear();
        return toView(config);
    }

    @Transactional
    public ModelConfigView update(String existingId, ModelConfigRequest request) {
        String sourceId = normalizeId(existingId);
        ResolvedModelConfig current = find(sourceId);
        if (current == null) {
            throw new IllegalArgumentException("要修改的模型不存在：" + sourceId);
        }
        String targetId = normalizeId(request.id());
        if (!hasText(targetId)) {
            targetId = sourceId;
        }
        validateModelId(targetId);
        if (BUILT_IN_IDS.contains(sourceId) && !sourceId.equals(targetId)) {
            throw new IllegalArgumentException("系统内置模型 " + sourceId + " 的 ID 不可修改");
        }
        if (!sourceId.equals(targetId) && find(targetId) != null) {
            throw new IllegalArgumentException("模型 ID 已存在：" + targetId);
        }

        long now = System.currentTimeMillis();
        String apiKey = hasText(request.apiKey()) ? request.apiKey().strip() : current.apiKey();
        ResolvedModelConfig config = new ResolvedModelConfig(
                targetId,
                required(request.name(), "name"),
                defaultText(request.provider(), "OpenAI Compatible"),
                trimTrailingSlash(required(request.baseUrl(), "baseUrl")),
                normalizeCompletionsPath(defaultText(request.completionsPath(), DEFAULT_COMPLETIONS_PATH)),
                apiKey,
                required(request.model(), "model"),
                request.temperature(),
                normalizeReasoningEffort(request.reasoningEffort()),
                normalizeThinkingType(request.thinkingType()),
                normalizeExtraBody(request.extraBody()),
                request.enabled() == null || request.enabled(),
                current.createdAt(),
                now);
        int updated = jdbcTemplate.update("""
                UPDATE app_model_configs SET
                    id = ?, name = ?, provider = ?, base_url = ?, completions_path = ?, api_key = ?,
                    model = ?, temperature = ?, reasoning_effort = ?, thinking_type = ?, extra_body = ?,
                    enabled = ?, updated_at = ?
                WHERE id = ?
                """,
                config.id(), config.name(), config.provider(), config.baseUrl(), config.completionsPath(),
                config.apiKey(), config.model(), config.temperature(), config.reasoningEffort(),
                config.thinkingType(), config.extraBody(), config.enabled(), config.updatedAt(), sourceId);
        if (updated != 1) {
            throw new IllegalArgumentException("模型 ID 修改失败，旧配置已保留");
        }
        ResolvedModelConfig persisted = find(targetId);
        if (persisted == null || (!sourceId.equals(targetId) && find(sourceId) != null)) {
            throw new IllegalArgumentException("模型 ID 修改后校验失败，事务已回滚");
        }
        modelCache.clear();
        return toView(persisted);
    }

    public void delete(String id) {
        String normalized = normalizeId(id);
        if (BUILT_IN_IDS.contains(normalized)) {
            throw new IllegalArgumentException("系统内置模型不能删除，可以编辑或禁用。");
        }
        jdbcTemplate.update("DELETE FROM app_model_configs WHERE id = ?", normalized);
        modelCache.clear();
    }

    public OpenAiChatModel chatModel(String modelId) {
        ResolvedModelConfig config = resolve(modelId);
        return modelCache.compute(config.cacheKey(), (key, current) -> current == null ? buildModel(config) : current);
    }

    public OpenAiChatOptions chatOptions(String modelId) {
        return chatOptions(modelId, null);
    }

    public OpenAiChatOptions chatOptions(String modelId, ModelRuntimeOptions runtimeOptions) {
        ResolvedModelConfig config = resolve(modelId);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(config.model());
        if (config.temperature() != null) {
            builder.temperature(config.temperature());
        }
        String reasoningEffort = normalizeReasoningEffort(runtimeOptions == null
                ? config.reasoningEffort()
                : defaultText(runtimeOptions.reasoningEffort(), config.reasoningEffort()));
        if (reasoningEffort != null) {
            builder.reasoningEffort(reasoningEffort);
        }
        Map<String, Object> extraBody = mergedExtraBody(config, runtimeOptions);
        if (!extraBody.isEmpty()) {
            builder.extraBody(extraBody);
        }
        return builder.build();
    }

    public ResolvedModelConfig resolve(String modelId) {
        String id = normalizeId(modelId);
        ResolvedModelConfig selected = hasText(id) ? find(id) : null;
        if (selected != null && selected.enabled()) {
            return selected;
        }
        ResolvedModelConfig fallback = findFirstEnabled();
        return fallback == null ? defaultConfig() : fallback;
    }

    private OpenAiChatModel buildModel(ResolvedModelConfig config) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .completionsPath(config.completionsPath())
                .build();
        return defaultModel.mutate()
                .openAiApi(api)
                .defaultOptions(chatOptions(config.id()))
                .build();
    }

    private ResolvedModelConfig findFirstEnabled() {
        return jdbcTemplate.query("""
                        SELECT id, name, provider, base_url, completions_path, api_key, model,
                               temperature, reasoning_effort, thinking_type, extra_body, enabled, created_at, updated_at
                        FROM app_model_configs
                        WHERE enabled = TRUE
                        ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, updated_at DESC
                        LIMIT 1
                        """,
                rs -> rs.next() ? mapConfig(rs) : null,
                DEFAULT_ID);
    }

    private ResolvedModelConfig find(String id) {
        if (!hasText(id)) {
            return null;
        }
        return jdbcTemplate.query("""
                        SELECT id, name, provider, base_url, completions_path, api_key, model,
                               temperature, reasoning_effort, thinking_type, extra_body, enabled, created_at, updated_at
                        FROM app_model_configs
                        WHERE id = ?
                        """,
                rs -> rs.next() ? mapConfig(rs) : null,
                id);
    }

    private ResolvedModelConfig mapConfig(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResolvedModelConfig(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("provider"),
                rs.getString("base_url"),
                rs.getString("completions_path"),
                rs.getString("api_key"),
                rs.getString("model"),
                rs.getObject("temperature", Double.class),
                rs.getString("reasoning_effort"),
                rs.getString("thinking_type"),
                rs.getString("extra_body"),
                rs.getBoolean("enabled"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private void ensureDefaultConfigs() {
        long now = System.currentTimeMillis();
        ensureDefaultConfig(new ResolvedModelConfig(
                "deepseek",
                "DeepSeek",
                "DeepSeek OpenAI Compatible",
                trimTrailingSlash(model1BaseUrl),
                normalizeCompletionsPath(model1CompletionsPath),
                model1ApiKey,
                defaultText(model1Name, "deepseek-v4-flash"),
                null,
                null,
                null,
                null,
                true,
                now,
                now));
        ensureDefaultConfig(new ResolvedModelConfig(
                DEFAULT_ID,
                "GPT",
                "OpenAI Compatible",
                trimTrailingSlash(model2BaseUrl),
                normalizeCompletionsPath(model2CompletionsPath),
                model2ApiKey,
                defaultText(model2Name, "gpt-5.5"),
                model2Temperature,
                null,
                null,
                null,
                true,
                now,
                now));
    }

    private void ensureDefaultConfig(ResolvedModelConfig config) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM app_model_configs WHERE id = ?",
                Integer.class, config.id());
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO app_model_configs (
                    id, name, provider, base_url, completions_path, api_key, model,
                    temperature, reasoning_effort, thinking_type, extra_body, enabled, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, TRUE, ?, ?)
                """,
                config.id(),
                config.name(),
                config.provider(),
                config.baseUrl(),
                config.completionsPath(),
                config.apiKey(),
                config.model(),
                config.temperature(),
                config.createdAt(),
                config.updatedAt());
    }

    private ResolvedModelConfig defaultConfig() {
        long now = System.currentTimeMillis();
        return new ResolvedModelConfig(DEFAULT_ID, "GPT", "OpenAI Compatible",
                trimTrailingSlash(model2BaseUrl), normalizeCompletionsPath(model2CompletionsPath),
                model2ApiKey, defaultText(model2Name, "gpt-5.5"), model2Temperature,
                null, null, null, true, now, now);
    }

    private ModelConfigView toView(ResolvedModelConfig config) {
        return new ModelConfigView(config.id(), config.name(), config.provider(), config.baseUrl(),
                config.completionsPath(), maskApiKey(config.apiKey()), hasText(config.apiKey()), config.model(),
                config.temperature(), config.reasoningEffort(), config.thinkingType(), config.extraBody(),
                config.enabled(), config.createdAt(), config.updatedAt());
    }

    private void addColumnIfMissing(String name, String type) {
        try {
            jdbcTemplate.execute("ALTER TABLE app_model_configs ADD COLUMN " + name + " " + type);
        } catch (Exception ignored) {
        }
    }

    private String normalizeId(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private void validateModelId(String id) {
        if (!MODEL_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("模型 ID 必须以字母或数字开头，且只能包含小写字母、数字、-、_，长度不超过 64 位");
        }
    }

    private String required(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.strip();
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value.strip() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimTrailingSlash(String value) {
        String text = defaultText(value, "");
        while (text.endsWith("/") && text.length() > 1) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private String normalizeCompletionsPath(String value) {
        String text = defaultText(value, DEFAULT_COMPLETIONS_PATH);
        return text.startsWith("/") ? text : "/" + text;
    }

    private String normalizeReasoningEffort(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "medium", "high", "xhigh", "max" -> normalized;
            default -> throw new IllegalArgumentException("reasoningEffort must be one of low, medium, high, xhigh, max");
        };
    }

    private String normalizeThinkingType(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "enabled", "disabled" -> normalized;
            default -> throw new IllegalArgumentException("thinkingType must be enabled or disabled");
        };
    }

    private String normalizeExtraBody(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(value, EXTRA_BODY_TYPE);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("extraBody must be a JSON object", e);
        }
    }

    private Map<String, Object> mergedExtraBody(ResolvedModelConfig config, ModelRuntimeOptions runtimeOptions) {
        Map<String, Object> merged = new LinkedHashMap<>();
        putAllExtraBody(merged, config.extraBody());
        String runtimeExtraBody = runtimeOptions == null ? null : runtimeOptions.extraBody();
        if (hasText(runtimeExtraBody)) {
            putAllExtraBody(merged, normalizeExtraBody(runtimeExtraBody));
        }
        String thinkingType = normalizeThinkingType(runtimeOptions == null
                ? config.thinkingType()
                : defaultText(runtimeOptions.thinkingType(), config.thinkingType()));
        if (thinkingType != null) {
            merged.put("thinking", Map.of("type", thinkingType));
        }
        return Map.copyOf(merged);
    }

    private void putAllExtraBody(Map<String, Object> target, String json) {
        if (!hasText(json)) {
            return;
        }
        try {
            target.putAll(objectMapper.readValue(json, EXTRA_BODY_TYPE));
        } catch (Exception e) {
            throw new IllegalArgumentException("extraBody must be a JSON object", e);
        }
    }

    private String maskApiKey(String value) {
        if (!hasText(value)) {
            return "";
        }
        String text = value.strip();
        if (text.length() <= 8) {
            return "••••";
        }
        return text.substring(0, 4) + "••••" + text.substring(text.length() - 4);
    }

    public record ModelConfigRequest(
            String id,
            String name,
            String provider,
            String baseUrl,
            String completionsPath,
            String apiKey,
            String model,
            Double temperature,
            String reasoningEffort,
            String thinkingType,
            String extraBody,
            Boolean enabled) {
    }

    public record ModelConfigView(
            String id,
            String name,
            String provider,
            String baseUrl,
            String completionsPath,
            String maskedApiKey,
            boolean hasApiKey,
            String model,
            Double temperature,
            String reasoningEffort,
            String thinkingType,
            String extraBody,
            boolean enabled,
            long createdAt,
            long updatedAt) {
    }

    public record ModelConfigExportView(
            String id,
            String name,
            String provider,
            String baseUrl,
            String completionsPath,
            String apiKey,
            String model,
            Double temperature,
            String reasoningEffort,
            String thinkingType,
            String extraBody,
            boolean enabled,
            long createdAt,
            long updatedAt) {
    }

    public record ResolvedModelConfig(
            String id,
            String name,
            String provider,
            String baseUrl,
            String completionsPath,
            String apiKey,
            String model,
            Double temperature,
            String reasoningEffort,
            String thinkingType,
            String extraBody,
            boolean enabled,
            long createdAt,
            long updatedAt) {

        String cacheKey() {
            return String.join("|",
                    id,
                    model,
                    baseUrl,
                    completionsPath,
                    Objects.toString(apiKey, ""),
                    Objects.toString(temperature, ""),
                    Objects.toString(reasoningEffort, ""),
                    Objects.toString(thinkingType, ""),
                    Objects.toString(extraBody, ""));
        }
    }
}
