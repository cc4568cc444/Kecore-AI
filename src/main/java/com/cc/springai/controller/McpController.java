package com.cc.springai.controller;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final ObjectProvider<List<McpSyncClient>> syncClientsProvider;
    private final Environment environment;

    public McpController(ObjectProvider<List<McpSyncClient>> syncClientsProvider,
                         Environment environment) {
        this.syncClientsProvider = syncClientsProvider;
        this.environment = environment;
    }

    @GetMapping("/servers")
    public List<McpServerStatus> servers() {
        List<McpSyncClient> clients = syncClientsProvider.getIfAvailable(List::of);
        List<ConfiguredMcpConnection> configuredConnections = configuredConnections();
        List<ConfiguredMcpConnection> unusedConfigured = new ArrayList<>(configuredConnections);
        List<McpServerStatus> servers = new ArrayList<>();

        for (int i = 0; i < clients.size(); i++) {
            McpSyncClient client = clients.get(i);
            McpSchema.Implementation serverInfo = safeServerInfo(client);
            McpSchema.Implementation clientInfo = safeClientInfo(client);
            List<McpToolInfo> tools = safeTools(client);
            ConfiguredMcpConnection configured = matchConfiguredConnection(clientInfo, unusedConfigured);
            unusedConfigured.remove(configured);
            servers.add(toStatus(client, serverInfo, clientInfo, tools, configured, i));
        }
        for (ConfiguredMcpConnection configured : unusedConfigured) {
            servers.add(configuredOnlyStatus(configured, servers.size()));
        }
        servers.sort((left, right) -> Integer.compare(
                configuredOrder(left.configuredName(), configuredConnections),
                configuredOrder(right.configuredName(), configuredConnections)));

        return servers;
    }

    private McpServerStatus toStatus(McpSyncClient client, McpSchema.Implementation serverInfo,
                                     McpSchema.Implementation clientInfo, List<McpToolInfo> tools,
                                     ConfiguredMcpConnection configured, int index) {
        boolean initialized = safeInitialized(client);
        String configuredName = configured == null ? "" : configured.name();
        String fallbackName = serverInfo == null || serverInfo.name() == null || serverInfo.name().isBlank()
                ? "mcp-" + (index + 1)
                : serverInfo.name();
        String name = firstText(configuredName, fallbackName);

        return new McpServerStatus(
                name,
                configuredName,
                serverInfo == null ? "" : serverInfo.name(),
                serverInfo == null ? "" : serverInfo.title(),
                serverInfo == null ? "" : serverInfo.version(),
                clientInfo == null ? "" : clientInfo.name(),
                initialized,
                initialized ? "loaded" : "client_not_initialized",
                configured == null ? "" : configured.type(),
                configured == null ? "" : configured.target(),
                tools.size(),
                tools
        );
    }

    private McpServerStatus configuredOnlyStatus(ConfiguredMcpConnection configured, int index) {
        String name = firstText(configured.name(), "mcp-" + (index + 1));
        return new McpServerStatus(
                name,
                configured.name(),
                "",
                "",
                "",
                "",
                false,
                "configured_not_loaded",
                configured.type(),
                configured.target(),
                0,
                List.of()
        );
    }

    private boolean safeInitialized(McpSyncClient client) {
        try {
            return client.isInitialized();
        } catch (Exception ignored) {
            return false;
        }
    }

    private McpSchema.Implementation safeServerInfo(McpSyncClient client) {
        try {
            return client.getServerInfo();
        } catch (Exception ignored) {
            return null;
        }
    }

    private McpSchema.Implementation safeClientInfo(McpSyncClient client) {
        try {
            return client.getClientInfo();
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<McpToolInfo> safeTools(McpSyncClient client) {
        try {
            McpSchema.ListToolsResult result = client.listTools();
            if (result == null || result.tools() == null) {
                return List.of();
            }
            return result.tools().stream()
                    .map(tool -> new McpToolInfo(tool.name(), tool.title(), tool.description()))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<ConfiguredMcpConnection> configuredConnections() {
        List<ConfiguredMcpConnection> connections = new ArrayList<>();
        connections.addAll(configuredHttpConnections("spring.ai.mcp.client.streamable-http.connections", "streamable-http"));
        connections.addAll(configuredStdioConnections("spring.ai.mcp.client.stdio.connections", "stdio"));
        return connections;
    }

    private List<ConfiguredMcpConnection> configuredHttpConnections(String prefix, String type) {
        Map<String, Object> values = bindConnectionMap(prefix);
        return values.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> config = connectionConfig(entry.getValue());
                    String url = text(config.get("url"));
                    String endpoint = text(config.get("endpoint"));
                    return new ConfiguredMcpConnection(entry.getKey(), type, (url + endpoint).strip());
                })
                .toList();
    }

    private List<ConfiguredMcpConnection> configuredStdioConnections(String prefix, String type) {
        Map<String, Object> values = bindConnectionMap(prefix);
        return values.entrySet().stream()
                .map(entry -> new ConfiguredMcpConnection(entry.getKey(), type,
                        text(connectionConfig(entry.getValue()).get("command"))))
                .toList();
    }

    private Map<String, Object> bindConnectionMap(String prefix) {
        return Binder.get(environment)
                .bind(prefix, Bindable.mapOf(String.class, Object.class))
                .orElseGet(LinkedHashMap::new);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> connectionConfig(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private ConfiguredMcpConnection matchConfiguredConnection(McpSchema.Implementation clientInfo,
                                                              List<ConfiguredMcpConnection> candidates) {
        String clientName = normalize(clientInfo == null ? "" : clientInfo.name());
        String clientTitle = normalize(clientInfo == null ? "" : clientInfo.title());

        ConfiguredMcpConnection match = firstMatching(candidates, connection ->
                !normalize(connection.name()).isBlank() && normalize(connection.name()).equals(clientTitle));
        if (match != null) {
            return match;
        }

        return firstMatching(candidates, connection -> {
            String name = normalize(connection.name());
            return !name.isBlank() && (name.equals(clientName)
                    || clientName.endsWith(" - " + name)
                    || clientName.endsWith("-" + name));
        });
    }

    private ConfiguredMcpConnection firstMatching(List<ConfiguredMcpConnection> candidates,
                                                  java.util.function.Predicate<ConfiguredMcpConnection> predicate) {
        for (ConfiguredMcpConnection candidate : candidates) {
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private int configuredOrder(String configuredName, List<ConfiguredMcpConnection> connections) {
        if (configuredName == null || configuredName.isBlank()) {
            return Integer.MAX_VALUE;
        }
        for (int i = 0; i < connections.size(); i++) {
            if (configuredName.equals(connections.get(i).name())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private String firstText(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase();
    }

    public record McpServerStatus(
            String name,
            String configuredName,
            String serverName,
            String title,
            String version,
            String clientName,
            boolean initialized,
            String loadState,
            String transportType,
            String target,
            int toolCount,
            List<McpToolInfo> tools
    ) {
    }

    public record McpToolInfo(String name, String title, String description) {
    }

    private record ConfiguredMcpConnection(String name, String type, String target) {
    }
}
