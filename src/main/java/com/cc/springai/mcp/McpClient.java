package com.cc.springai.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public interface McpClient {
    JsonNode callTool(String serverName, String toolName, JsonNode arguments) throws Exception;
}
