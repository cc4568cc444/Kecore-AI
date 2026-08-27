package com.cc.springai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.mcp.McpClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

public final class McpToolCallback implements ToolCallback {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final String EMPTY_OBJECT_SCHEMA = """
            {"type":"object","properties":{},"additionalProperties":false}
            """;

    private final McpClient client;
    private final McpToolDefinition tool;
    private final ObjectMapper objectMapper;
    private final ToolDefinition toolDefinition;
    private final ToolMetadata toolMetadata;

    public McpToolCallback(McpClient client, McpToolDefinition tool, ObjectMapper objectMapper) {
        this.client = client;
        this.tool = tool;
        this.objectMapper = objectMapper;
        this.toolDefinition = buildToolDefinition(tool);
        this.toolMetadata = ToolMetadata.builder()
                .returnDirect(false)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return toolMetadata;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            JsonNode arguments = parseArguments(toolInput);
            JsonNode result = client.callTool(tool.serverName(), tool.originalName(), arguments);
            return normalizeResult(result);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return toJson(Map.of("isError", true, "message", message));
        }
    }

    private ToolDefinition buildToolDefinition(McpToolDefinition tool) {
        if (!TOOL_NAME_PATTERN.matcher(tool.exposedName()).matches()) {
            throw new IllegalArgumentException("非法工具名: " + tool.exposedName());
        }

        return ToolDefinition.builder()
                .name(tool.exposedName())
                .description(blankToDefault(tool.description(), "MCP tool: " + tool.originalName()))
                .inputSchema(blankToDefault(tool.inputSchemaJson(), EMPTY_OBJECT_SCHEMA))
                .build();
    }

    private JsonNode parseArguments(String toolInput) throws Exception {
        if (toolInput == null || toolInput.isBlank()) {
            return objectMapper.createObjectNode();
        }

        JsonNode node = objectMapper.readTree(toolInput);
        if (node == null || node.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("MCP 工具参数必须是 JSON object");
        }
        return node;
    }

    private String normalizeResult(JsonNode result) throws Exception {
        if (result == null || result.isNull()) {
            return "";
        }

        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            StringBuilder text = new StringBuilder();
            Iterator<JsonNode> iterator = content.elements();
            while (iterator.hasNext()) {
                JsonNode item = iterator.next();
                if ("text".equals(item.path("type").asText()) && item.has("text")) {
                    if (!text.isEmpty()) {
                        text.append("\n");
                    }
                    text.append(item.path("text").asText());
                }
            }
            if (!text.isEmpty()) {
                return text.toString();
            }
        }

        return objectMapper.writeValueAsString(result);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{\"isError\":true,\"message\":\"MCP tool error\"}";
        }
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record McpToolDefinition(
            String serverName,
            String originalName,
            String exposedName,
            String description,
            String inputSchemaJson
    ) {
    }
}
