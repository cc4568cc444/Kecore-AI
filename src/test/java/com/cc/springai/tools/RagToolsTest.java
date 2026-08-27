package com.cc.springai.tools;

import com.cc.springai.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RagToolsTest {

    private static final String CONVERSATION_ID = "c1";

    @Test
    void searchesEachPipeSeparatedQuery() {
        RagService ragService = mock(RagService.class);
        when(ragService.search("alpha", CONVERSATION_ID, 5)).thenReturn(List.of(
                new RagService.RagChunk("alpha result", 0.9, Map.of("source", "a.pdf"))
        ));
        when(ragService.search("beta", CONVERSATION_ID, 5)).thenReturn(List.of(
                new RagService.RagChunk("beta result", 0.8, Map.of("source", "b.pdf"))
        ));
        RagTools tools = new RagTools(ragService);

        String result = tools.ragSearch(" alpha | beta ", toolContext());

        assertThat(result)
                .contains("query: alpha")
                .contains("alpha result")
                .contains("query: beta")
                .contains("beta result")
                .contains("===");
        verify(ragService).search("alpha", CONVERSATION_ID, 5);
        verify(ragService).search("beta", CONVERSATION_ID, 5);
        verifyNoMoreInteractions(ragService);
    }

    @Test
    void ignoresBlankBatchItems() {
        RagService ragService = mock(RagService.class);
        when(ragService.search("alpha", CONVERSATION_ID, 5)).thenReturn(List.of());
        when(ragService.search("beta", CONVERSATION_ID, 5)).thenReturn(List.of());
        RagTools tools = new RagTools(ragService);

        String result = tools.ragSearch("alpha || beta | ", toolContext());

        assertThat(result)
                .contains("query: alpha")
                .contains("query: beta")
                .contains("已上传文档中没有检索到相关片段。");
        verify(ragService).search("alpha", CONVERSATION_ID, 5);
        verify(ragService).search("beta", CONVERSATION_ID, 5);
        verifyNoMoreInteractions(ragService);
    }

    @Test
    void keepsSingleQueryResponseFormat() {
        RagService ragService = mock(RagService.class);
        when(ragService.search("alpha", CONVERSATION_ID, 5)).thenReturn(List.of(
                new RagService.RagChunk("alpha result", 0.9, Map.of())
        ));
        RagTools tools = new RagTools(ragService);

        String result = tools.ragSearch("alpha", toolContext());

        assertThat(result)
                .contains("alpha result")
                .doesNotContain("query: alpha");
        verify(ragService).search("alpha", CONVERSATION_ID, 5);
        verifyNoMoreInteractions(ragService);
    }

    private ToolContext toolContext() {
        return new ToolContext(Map.of("conversationId", CONVERSATION_ID));
    }
}
