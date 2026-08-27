package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FinancialRagServiceTest {

    private final FinancialRagService service = new FinancialRagService(
            mock(EmbeddingModel.class),
            mock(JdbcTemplate.class),
            new ObjectMapper(),
            mock(ChatClient.class),
            mock(ChatMemory.class),
            mock(ModelConfigService.class)
    );

    @Test
    void parsesRetrievalPlanWithResolvedQuestionIntentAndSubQueries() {
        FinancialRagService.FinancialRetrievalPlan plan = service.parseRetrievalPlan(
                "2023 呢？",
                """
                        {
                          "translatedQuestion": "What about 2023?",
                          "resolvedQuestion": "What was Apple's revenue in fiscal 2023?",
                          "intent": "factual_metric",
                          "retrievalQueries": [
                            "Apple fiscal 2023 revenue net sales",
                            "AAPL 2023 consolidated statements net sales"
                          ]
                        }
                        """);

        assertThat(plan.resolvedQuestion()).isEqualTo("What was Apple's revenue in fiscal 2023?");
        assertThat(plan.intent()).isEqualTo("factual_metric");
        assertThat(plan.queries()).hasSize(5);
        assertThat(plan.queries().get(0)).isEqualTo("What about 2023?");
        assertThat(plan.queries())
                .contains("Apple fiscal 2023 revenue net sales")
                .contains("AAPL 2023 consolidated statements net sales")
                .contains("What was Apple's revenue in fiscal 2023?");
        assertThat(plan.displayQuery()).contains(" | ");
    }

    @Test
    void malformedPlannerOutputFallsBackToExpandedQuestion() {
        FinancialRagService.FinancialRetrievalPlan plan = service.parseRetrievalPlan(
                "Apple 2024 revenue 是多少？",
                "not json");

        assertThat(plan.resolvedQuestion()).isEqualTo("Apple 2024 revenue 是多少？");
        assertThat(plan.intent()).isEqualTo("factual_metric");
        assertThat(plan.queries()).hasSize(5);
        assertThat(plan.queries().get(0)).isEqualTo("Apple 2024 revenue 是多少？");
        assertThat(String.join(" ", plan.queries())).contains("net sales");
    }
}
