package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaperRagServiceTest {

    private final PaperRagService service = new PaperRagService(
            mock(EmbeddingModel.class),
            mock(JdbcTemplate.class),
            new ObjectMapper(),
            mock(ChatClient.class),
            mock(ChatMemory.class),
            mock(ModelConfigService.class)
    );

    @Test
    void parsesAcademicRetrievalPlanWithoutLosingMetrics() {
        PaperRagService.RetrievalPlan plan = service.parseRetrievalPlan(
                "这篇论文的 F1 提升了多少？",
                """
                        {
                          "translatedQuestion": "How much did the F1 score improve?",
                          "resolvedQuestion": "How much did Model A improve F1 over BERT on SQuAD?",
                          "paperTitle": "Example Paper",
                          "intent": "numeric_result",
                          "retrievalQueries": [
                            "Model A BERT SQuAD F1 improvement",
                            "experimental results table F1"
                          ]
                        }
                        """);

        assertThat(plan.paperTitle()).isEqualTo("Example Paper");
        assertThat(plan.intent()).isEqualTo("numeric_result");
        assertThat(plan.queries()).hasSize(4);
        assertThat(plan.displayQuery()).contains("F1").contains("SQuAD");
    }

    @Test
    void malformedPlannerOutputFallsBackToDeterministicQueries() {
        PaperRagService.RetrievalPlan plan = service.parseRetrievalPlan(
                "What dataset size was used?", "not-json");

        assertThat(plan.intent()).isEqualTo("numeric_result");
        assertThat(plan.queries()).hasSize(4);
        assertThat(plan.queries().get(0)).isEqualTo("What dataset size was used?");
    }

    @Test
    void tokenizerPreservesScientificNumbersAndModelNames() {
        assertThat(service.tokenize("BERT-base reached 87.4% F1 on SQuAD 2.0"))
                .contains("bert-base", "87.4%", "f1", "squad", "2.0");
    }

    @Test
    void parsesIndependentEvaluationAblationModes() {
        assertThat(PaperRagService.EvaluationRetrievalMode.fromValue("vector"))
                .isEqualTo(PaperRagService.EvaluationRetrievalMode.VECTOR);
        assertThat(PaperRagService.EvaluationRetrievalMode.fromValue("bm25"))
                .isEqualTo(PaperRagService.EvaluationRetrievalMode.BM25);
        assertThat(PaperRagService.EvaluationRetrievalMode.fromValue("hybrid"))
                .isEqualTo(PaperRagService.EvaluationRetrievalMode.HYBRID);
        assertThat(PaperRagService.EvaluationRetrievalMode.fromValue("hybrid-rerank"))
                .isEqualTo(PaperRagService.EvaluationRetrievalMode.HYBRID_RERANK);
    }
}
