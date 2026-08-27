package com.cc.springai.registry;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SpringAiMcpToolRegistry implements McpToolRegistry {

    private static final Pattern INVALID_TOOL_NAME_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final int MAX_TOOL_NAME_LENGTH = 64;

    private final ObjectProvider<SyncMcpToolCallbackProvider> syncProvider;

    public SpringAiMcpToolRegistry(ObjectProvider<SyncMcpToolCallbackProvider> syncProvider) {
        this.syncProvider = syncProvider;
    }

    @Override
    public List<ToolCallback> toolCallbacks() {
        SyncMcpToolCallbackProvider provider = syncProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        Set<String> usedNames = new HashSet<>();
        return Arrays.stream(provider.getToolCallbacks())
                .map(callback -> sanitizeToolCallback(callback, usedNames))
                .toList();
    }

    private ToolCallback sanitizeToolCallback(ToolCallback callback, Set<String> usedNames) {
        ToolDefinition definition = callback.getToolDefinition();
        String originalName = definition.name();
        String sanitizedName = uniqueToolName(sanitizeToolName(originalName), usedNames);
        if (sanitizedName.equals(originalName)) {
            return callback;
        }
        return new SanitizedToolCallback(callback, sanitizedName, originalName);
    }

    private String sanitizeToolName(String name) {
        String sanitized = INVALID_TOOL_NAME_CHARS.matcher(name == null ? "" : name).replaceAll("_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = trimToolName(sanitized);
        if (sanitized.isBlank()) {
            return "mcp_tool";
        }
        return sanitized;
    }

    private String uniqueToolName(String name, Set<String> usedNames) {
        String baseName = trimToolName(name);
        String candidate = baseName;
        int index = 2;
        while (!usedNames.add(candidate)) {
            String suffix = "_" + index++;
            candidate = trimToolName(baseName, suffix.length()) + suffix;
        }
        return candidate;
    }

    private String trimToolName(String name) {
        return trimToolName(name, 0);
    }

    private String trimToolName(String name, int reservedSuffixLength) {
        int maxLength = Math.max(1, MAX_TOOL_NAME_LENGTH - reservedSuffixLength);
        return name.length() <= maxLength ? name : name.substring(0, maxLength);
    }

    private static final class SanitizedToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final ToolDefinition toolDefinition;

        private SanitizedToolCallback(ToolCallback delegate, String sanitizedName, String originalName) {
            this.delegate = delegate;
            ToolDefinition originalDefinition = delegate.getToolDefinition();
            this.toolDefinition = ToolDefinition.builder()
                    .name(sanitizedName)
                    .description(originalDefinition.description()
                            + "\n\nOriginal MCP tool name: " + originalName)
                    .inputSchema(originalDefinition.inputSchema())
                    .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}
