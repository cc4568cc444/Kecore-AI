package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

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
                          "subTasks": [{
                            "id": "retrieve_aapl_2023_revenue",
                            "query": "Apple net sales",
                            "companies": ["AAPL"],
                            "years": ["2023"],
                            "metric": "revenue",
                            "operation": "retrieve",
                            "modality": "table",
                            "dependsOn": []
                          }],
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
        assertThat(plan.subTasks()).hasSize(1);
        assertThat(plan.subTasks().get(0).companies()).containsExactly("AAPL");
        assertThat(plan.subTasks().get(0).years()).containsExactly("2023");
    }

    @Test
    void normalizesFyPrefixedPlannerYears() {
        FinancialRagService.FinancialRetrievalPlan plan = service.parseRetrievalPlan(
                "age change", """
                        {
                          "resolvedQuestion":"age change",
                          "intent":"comparison",
                          "subTasks":[{
                            "id":"retrieve_age",
                            "query":"Guillermo Bichara age",
                            "companies":["LIN"],
                            "years":["FY2022","fiscal year 2023","2024"],
                            "metric":"age",
                            "operation":"retrieve",
                            "modality":"text",
                            "dependsOn":[]
                          }],
                          "retrievalQueries":["age change"]
                        }
                        """);

        assertThat(plan.subTasks().get(0).years()).containsExactly("2022", "2023", "2024");
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
        assertThat(plan.subTasks()).hasSize(1);
    }

    @Test
    void metadataFilterIncludesRequestedDocumentModality() {
        FinancialRagService.SqlWhere where = service.whereClause(
                new FinancialRagService.RetrievalFilters("AAPL_2024.html", "AAPL", "2024", "table"));

        assertThat(where.sql()).contains("source_file = ?", "company = ?", "year = ?", "chunk_type = ?");
        assertThat(where.params()).containsExactly("AAPL_2024.html", "AAPL", "2024", "table");
    }

    @Test
    void treatsTextModalityAsSoftFilterForMixedParserChunks() {
        FinancialRagService.RetrievalFilters filters = service.explicitFilters(
                "multidoc_full_chunks", "", "", "text");

        assertThat(filters.chunkType()).isBlank();
    }

    @Test
    void keepsTableModalityAsHardFilter() {
        FinancialRagService.RetrievalFilters filters = service.explicitFilters(
                "multidoc_full_chunks", "", "", "table");

        assertThat(filters.chunkType()).isEqualTo("table");
    }

    @Test
    void buildsSafeOrQueryForFullCorpusLexicalRecall() {
        assertThat(service.lexicalTsQuery("Apple 2024 revenue / net sales?"))
                .isEqualTo("apple | 2024 | revenue | net | sales");
    }

    @Test
    void extractsExplicitCompanyYearPairsWithoutCrossProduct() {
        List<FinancialRagService.CompanyYearScope> scopes = service.explicitCompanyYearScopes(
                "Compare LIN in FY2024 with ECL's FY2022 filing.", Set.of("LIN", "ECL"));

        assertThat(scopes).containsExactly(
                new FinancialRagService.CompanyYearScope("LIN", "2024"),
                new FinancialRagService.CompanyYearScope("ECL", "2022"));
    }

    @Test
    void extractsPersonNameForCompanyDiscovery() {
        assertThat(service.namedEntityPhrases(
                "How did the age of Guillermo Bichara change from FY2022 to FY2023?"))
                .containsExactly("Guillermo Bichara");
    }

    @Test
    void forcesTextRetrievalForFilingLanguageAndExactQuoteTasks() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_ecl_language", "ECL FY2022 exact quote from Item 7 MD&A",
                List.of("ECL"), List.of("2022"), "quantitative sales driver statement",
                "retrieve", "table", List.of());

        assertThat(service.effectiveTaskModality(task)).isEqualTo("text");
    }

    @Test
    void rendersEveryRetrievalTaskAsAnAnswerCoverageRequirement() {
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "question", "question", "comparison", List.of("question"), List.of(
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_lin_2024", "LIN filing language", List.of("LIN"), List.of("2024"),
                        "segment detail reference", "retrieve", "text", List.of()),
                new FinancialRagService.FinancialRetrievalTask(
                        "compare", "compare both", List.of("LIN", "ECL"), List.of("2024", "2022"),
                        "comparison", "compare", "hybrid", List.of("retrieve_lin_2024"))));

        assertThat(service.requiredCoverage(plan))
                .contains("retrieve_lin_2024", "companies=LIN", "years=2024", "segment detail reference")
                .doesNotContain("compare | companies");
    }

    @Test
    void asksForConciseInScopeQualitativeComparisons() {
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "Compare AEP and ED", "Compare AEP and ED", "comparison", List.of("competition"), List.of());
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(), List.of(), List.of());

        String answerPrompt = service.answerPrompt("Compare AEP and ED", plan, ledger);

        assertThat(answerPrompt).contains(
                "normally 120-180 words and never more than 220 words",
                "at most one concise bullet per company/year",
                "outside the requested",
                "do not refuse merely because one side lacks a parallel discussion",
                "state that asymmetry concisely");
    }

    @Test
    void detectsTaskThatIsOnlyCitedInSourcesAsMissingFromAnswerBody() {
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "Compare AMD and MSFT", "Compare AMD and MSFT", "comparison", List.of(), List.of(
                retrievalTask("retrieve_amd", "AMD"), retrievalTask("retrieve_msft", "MSFT")));
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(List.of(
                evidence("E1", "retrieve_amd"), evidence("E2", "retrieve_msft")), List.of(), List.of());

        List<String> missing = service.missingAnswerCoverage(
                "AMD had $5.1 billion [E1].\n\nSources:\n[E1] AMD_2024.html\n[E2] MSFT_2024.html",
                plan, ledger);

        assertThat(missing).hasSize(1).first().asString().contains("retrieve_msft");
    }

    @Test
    void compactsEvidenceWhileRetainingDocumentsForEveryRetrievalTask() {
        List<FinancialEvidenceLedger.EvidenceDocument> documents = List.of(
                evidence("E1", "task_a"), evidence("E2", "task_a"), evidence("E3", "task_b"),
                evidence("E4", "task_b"), evidence("E5", ""));

        List<FinancialEvidenceLedger.EvidenceDocument> compact =
                service.compactExtractionDocuments(documents, 4);

        assertThat(compact).extracting(FinancialEvidenceLedger.EvidenceDocument::evidenceId)
                .containsExactly("E1", "E2", "E3", "E4");
    }

    @Test
    void bindsUnknownPlannerCompanyToCompanyDiscoveredFromNamedEntity() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_bichara_2023", "Guillermo Bichara age", List.of("UNKNOWN"), List.of("2023"),
                "age", "retrieve", "text", List.of());

        FinancialRagService.FinancialRetrievalTask bound = service.bindDiscoveredCompany(
                task, List.of("LIN"), Set.of("LIN", "ECL"));

        assertThat(bound.companies()).containsExactly("LIN");
        assertThat(bound.years()).containsExactly("2023");
    }

    @Test
    void keepsExactMatchedChildWhenParentContextDoesNotContainIt() {
        String combined = service.childWithParentContext(
                "Guillermo Bichara, 49, Executive Vice President",
                "Section: Executive Officers\nOther surrounding biographies");

        assertThat(combined).startsWith("Guillermo Bichara, 49")
                .contains("Parent context:", "Other surrounding biographies");
    }

    @Test
    void avoidsDuplicatingChildAlreadyPresentInParentContext() {
        String parent = "Executive Officers\nGuillermo Bichara, 49, Executive Vice President";

        assertThat(service.childWithParentContext("Guillermo Bichara, 49", parent)).isEqualTo(parent);
    }

    private FinancialRagService.FinancialRetrievalTask retrievalTask(String id, String company) {
        return new FinancialRagService.FinancialRetrievalTask(
                id, company + " cash", List.of(company), List.of("2024"), "cash", "retrieve", "table", List.of());
    }

    private FinancialEvidenceLedger.EvidenceDocument evidence(String id, String taskId) {
        return new FinancialEvidenceLedger.EvidenceDocument(
                id, "chunk-" + id, "TEST_2024.html", "TEST", "2024", "table", "Item 8", "Statements",
                "A sufficiently long evidence row with value 100.", taskId.isBlank() ? List.of() : List.of(taskId));
    }
}
