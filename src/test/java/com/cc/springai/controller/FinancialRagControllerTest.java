package com.cc.springai.controller;

import com.cc.springai.service.ChatMemoryRewindService;
import com.cc.springai.service.FinancialRagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialRagControllerTest {

    @Test
    void delegatesStructuredAnalysisWithNormalizedConversationId() {
        FinancialRagService service = mock(FinancialRagService.class);
        FinancialRagController controller = new FinancialRagController(
                service, mock(ChatMemoryRewindService.class), new ObjectMapper());
        FinancialRagService.FinancialAnalysisResult expected = new FinancialRagService.FinancialAnalysisResult(
                "answer", "resolved", "comparison", List.of(), List.of(), List.of(), List.of(),
                new FinancialRagService.CitationAudit(true, List.of()), 1, 2, 3, 6, "",
                "NORMAL", List.of());
        when(service.analyze("question", "default", "model", FinancialRagService.FinancialRetrievalMode.PARENT_CHILD))
                .thenReturn(expected);

        FinancialRagService.FinancialAnalysisResult actual =
                controller.analyze("question", " ", "model", "parent-child");

        assertThat(actual).isSameAs(expected);
        verify(service).analyze("question", "default", "model", FinancialRagService.FinancialRetrievalMode.PARENT_CHILD);
    }
}
