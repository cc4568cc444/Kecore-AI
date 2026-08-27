package com.cc.springai.config;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(McpHttpClientConfiguration.McpHttpHeaderProperties.class)
public class McpHttpClientConfiguration {

    @Bean
    public McpSyncHttpClientRequestCustomizer mcpHttpHeaderCustomizer(McpHttpHeaderProperties properties) {
        return (builder, method, uri, body, context) -> properties.headersFor(uri)
                .forEach((name, value) -> {
                    if (value != null && !value.isBlank()) {
                        builder.header(name, value);
                    }
                });
    }

    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return McpToolNamePrefixGenerator.noPrefix();
    }

    @Bean
    public McpSyncClientCustomizer mcpClientInfoCustomizer() {
        return (name, spec) -> spec.clientInfo(new McpSchema.Implementation(name, name, "1.0.0"));
    }

    @ConfigurationProperties(prefix = "app.mcp.http")
    public static class McpHttpHeaderProperties {

        private Map<String, Map<String, String>> headers = new HashMap<>();

        public Map<String, Map<String, String>> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, Map<String, String>> headers) {
            this.headers = headers;
        }

        Map<String, String> headersFor(URI uri) {
            String baseUrl = uri.getScheme() + "://" + uri.getAuthority();
            return headers.getOrDefault(baseUrl, Map.of());
        }
    }
}
