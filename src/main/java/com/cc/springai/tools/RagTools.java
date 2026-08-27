package com.cc.springai.tools;

import com.cc.springai.service.RagService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class RagTools {

    private static final int DEFAULT_TOP_K = 5;

    private final RagService ragService;

    public RagTools(RagService ragService) {
        this.ragService = ragService;
    }

    @Tool(description = "从已上传的 PDF 知识库中检索与用户问题最相关的文档片段。回答 PDF、文档、资料库、知识库相关问题前应优先调用此工具。支持用 | 分隔多条查询进行批量检索。")
    public String ragSearch(
            @ToolParam(description = "用户的问题或需要检索的关键词。多条查询可用 | 分隔，例如：问题一|问题二") String query,
            ToolContext toolContext
    ) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        List<String> queries = splitQueries(query);
        if (queries.size() == 1) {
            return formatSearchResult(ragService.search(queries.get(0), conversationId, DEFAULT_TOP_K));
        }

        return queries.stream()
                .map(currentQuery -> """
                        query: %s
                        %s
                        """.formatted(currentQuery, formatSearchResult(
                        ragService.search(currentQuery, conversationId, DEFAULT_TOP_K))))
                .collect(Collectors.joining("\n===\n"));
    }

    private List<String> splitQueries(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }

        List<String> queries = List.of(query.split("\\|")).stream()
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        return queries;
    }

    private String formatSearchResult(List<RagService.RagChunk> chunks) {
        if (chunks.isEmpty()) {
            return "已上传文档中没有检索到相关片段。";
        }

        return chunks.stream()
                .map(chunk -> """
                        score: %s
                        metadata: %s
                        text:
                        %s
                        """.formatted(chunk.score(), chunk.metadata(), chunk.text()))
                .collect(Collectors.joining("\n---\n"));
    }
}
