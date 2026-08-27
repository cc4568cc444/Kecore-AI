package com.cc.springai.registry;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public interface McpToolRegistry {
    List<ToolCallback> toolCallbacks();
}
