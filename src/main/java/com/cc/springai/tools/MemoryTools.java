package com.cc.springai.tools;

import com.cc.springai.service.MemoryService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class MemoryTools {

    private final MemoryService memoryService;

    public MemoryTools(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(name = "save_memory", description = """
            Save one durable memory as its own markdown file and update MEMORY.md.
            Parameters:
            - name: stable identifier such as prefer_tabs or avoid_mock_heavy_tests.
            - description: one-line summary used in MEMORY.md.
            - type: one of user, feedback, project, reference.
            - opportunity: when this memory should be loaded, in plain text.
            - content: the full memory body，必须完整记录用户要记忆的内容，避免忽略重要细节。
            The tool writes the save time itself, to the minute, into both the MEMORY.md index and the saved markdown file.
            Do not save secrets, tokens, passwords, private personal data, one-off plans, unverified guesses, or large logs.
            """)
    public String saveMemory(
            @ToolParam(description = "Stable memory identifier") String name,
            @ToolParam(description = "One-line memory summary for MEMORY.md") String description,
            @ToolParam(description = "Memory type: user, feedback, project, or reference") String type,
            @ToolParam(description = "Load opportunity text, 例如首次会话时、每次会话时、需要周报时、写入文件时、读取文件时、用户提到xx信息时等等，不要超过30个字") String opportunity,
            @ToolParam(description = "Full durable memory body") String content,
            ToolContext toolContext) {
        return memoryService.saveMemory(workingDirectory(toolContext), name, description, type, opportunity, content);
    }

    @Tool(name = "forget_memory", description = """
            Delete one durable memory and remove its MEMORY.md index entry.
            Use when the user explicitly asks to forget, delete, or stop remembering a memory.
            Parameters:
            - name: stable identifier of the memory.
            - type: one of user, feedback, project, reference.
            This tool is not auto-approved.
            """)
    public String forgetMemory(
            @ToolParam(description = "Stable memory identifier to delete") String name,
            @ToolParam(description = "Memory type: user, feedback, project, or reference") String type,
            ToolContext toolContext) {
        return memoryService.forgetMemory(workingDirectory(toolContext), name, type);
    }

    @Tool(name = "read_memory", description = """
            Read the full markdown content of one durable memory file.
            Use it when you need the exact memory body or want to inspect a specific stored memory.
            或者在达到MEMORY Index中某条记忆的触发时机(opportunity)且当前上下文没有读取过相应记忆时读取相应记忆。
            Parameters:
            - name: stable identifier of the memory.
            - type: one of user, feedback, project, reference.
            This tool is auto-executable and does not need approval.
            """)
    public String readMemory(
            @ToolParam(description = "Stable memory identifier to read") String name,
            @ToolParam(description = "Memory type: user, feedback, project, or reference") String type,
            ToolContext toolContext) {
        return memoryService.readMemory(workingDirectory(toolContext), name, type);
    }

    private String workingDirectory(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return "";
        }
        Object value = toolContext.getContext().get(BasicTools.WORKING_DIRECTORY_CONTEXT_KEY);
        return value == null ? "" : String.valueOf(value);
    }
}
