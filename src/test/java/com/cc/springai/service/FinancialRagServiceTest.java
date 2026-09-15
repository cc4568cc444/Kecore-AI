package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
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
    void appliesDatasetItemScopeToEveryOrdinaryRetrievalTask() {
        var itemEight = new FinancialRagService.FinancialRetrievalTask(
                "aep", "AEP non-GAAP Item 8", List.of("AEP"), List.of("2024"),
                "non-GAAP disclosure", "retrieve", "text", List.of());
        var unscoped = new FinancialRagService.FinancialRetrievalTask(
                "xel", "XEL non-GAAP disclosure", List.of("XEL"), List.of("2024"),
                "non-GAAP disclosure", "retrieve", "text", List.of());

        assertThat(service.enforceRequestedItemScope(List.of(itemEight, unscoped), "Item 7"))
                .extracting(FinancialRagService.FinancialRetrievalTask::query)
                .allMatch(query -> query.contains("Item 7"))
                .noneMatch(query -> query.contains("Item 8"));
    }

    @Test
    void rejectsPercentagePlanForGenericChangeButKeepsExplicitGrowthRate() {
        var plan = new FinancialEvidenceLedger.CalculationPlan(
                "growth", FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                List.of(new FinancialEvidenceLedger.CalculationOperand(
                        "start", "t1", "LLY", "2023", "candidate count")),
                "percent", "unit", 1, 0);

        assertThat(service.rejectUnrequestedPercentageChanges(
                "How did the number of candidates change from 2023 to 2024?", List.of(plan))).isEmpty();
        assertThat(service.rejectUnrequestedPercentageChanges(
                "What was the percentage growth from 2023 to 2024?", List.of(plan))).containsExactly(plan);
    }

    @Test
    void rejectsUnrequestedCagrButKeepsExplicitCompoundGrowthRequest() {
        var plan = new FinancialEvidenceLedger.CalculationPlan(
                "cagr", FinancialEvidenceLedger.CalculationOperator.CAGR,
                List.of(new FinancialEvidenceLedger.CalculationOperand(
                        "start", "t1", "LLY", "2022", "diluted earnings per share")),
                "percent", "unit", 1, 2);

        assertThat(service.rejectUnrequestedCagr(
                "What were diluted EPS in each year and what was the overall trend?", List.of(plan))).isEmpty();
        assertThat(service.rejectUnrequestedCagr(
                "What was the compound annual growth rate of diluted EPS?", List.of(plan))).containsExactly(plan);
    }

    @Test
    void repairsReplacementCharactersInManagementSectionTitle() {
        assertThat(service.readableSectionTitle(
                "Management\uFFFD\uFFFDs Discussion and Analysis of Financial Condition"))
                .isEqualTo("Management's Discussion and Analysis of Financial Condition");
    }

    @Test
    void inheritsItemSevenForUnlabelledTablesButKeepsExplicitTableItem() {
        var inherited = new FinancialRagService.FinancialChunk(
                "table-1", "LLY_2024.html", "table", "| Revenue | 45,042.7 |",
                Map.of("company", "LLY", "year", "2024", "parent_context",
                        "Item 7 Management's Discussion and Analysis of Results of Operations"), 0.9);
        var explicit = new FinancialRagService.FinancialChunk(
                "table-2", "AEP_2024.html", "table", "| Operating earnings | 1,000 |",
                Map.of("company", "AEP", "year", "2024", "item", "Item 8", "parent_context",
                        "Item 7 Management's Discussion and Analysis"), 0.8);

        assertThat(service.canonicalItem(inherited)).isEqualTo("Item 7");
        assertThat(service.canonicalItem(explicit)).isEqualTo("Item 8");
    }

    @Test
    void repairsStaleItemMetadataForIndependentAuditorReport() {
        var auditorReport = new FinancialRagService.FinancialChunk(
                "ed-audit", "ED_2024.html", "table", "Table header: 84 | Annual Report",
                Map.of("company", "ED", "year", "2024", "item", "Item 1A", "parent_context",
                        "Report of Independent Registered Public Accounting Firm. "
                                + "Opinions on the Financial Statements and Internal Control over Financial Reporting. "
                                + "In our opinion, the consolidated financial statements present fairly in conformity with GAAP."),
                0.9);

        assertThat(service.canonicalItem(auditorReport)).isEqualTo("Item 8");
    }

    @Test
    void keepsExplicitQuantitativeAndHeaderTasksInTableModality() {
        var revenue = new FinancialRagService.FinancialRetrievalTask(
                "revenue", "Item 7 MD&A total revenue", List.of("LLY"), List.of("2024"),
                "total revenue", "retrieve", "table", List.of());
        var headers = new FinancialRagService.FinancialRetrievalTask(
                "headers", "financial statement table headers fiscal years", List.of("NFLX"), List.of("2024"),
                "table headers", "retrieve", "table", List.of());

        assertThat(service.effectiveTaskModality(revenue)).isEqualTo("table");
        assertThat(service.effectiveTaskModality(headers)).isEqualTo("table");
    }

    @Test
    void doesNotDowngradeNumericFinancialStatementQueryToTextEvidence() {
        var task = new FinancialRagService.FinancialRetrievalTask(
                "net_income", "LLY net income consolidated financial statements", List.of("LLY"), List.of("2024"),
                "net income", "retrieve", "hybrid", List.of());
        var sensitivity = new FinancialRagService.FinancialChunk(
                "sensitivity", "LLY_2024.html", "text",
                "If an assumption changed, net income would be affected by $35.9 million.",
                Map.of("company", "LLY", "year", "2024", "item", "Item 7"), 0.9)
                .withMatchedTask("net_income");

        assertThat(service.effectiveTaskModality(task)).isEqualTo("hybrid");
        assertThat(service.evidenceSupport(task, sensitivity))
                .isEqualTo(FinancialRagService.EvidenceSupport.SCOPE_ONLY);
        assertThat(service.missingDirectSupport(List.of(task), List.of(sensitivity)))
                .singleElement().asString().contains("missing_direct_support", "net income");
    }

    @Test
    void requiresCompleteTopicEvidenceForAuditAndGuaranteeTasks() {
        var audit = new FinancialRagService.FinancialRetrievalTask(
                "audit", "ED FY2024 audit opinion", List.of("ED"), List.of("2024"),
                "audit_opinion", "retrieve", "text", List.of());
        var unrelated = new FinancialRagService.FinancialChunk(
                "sale", "ED_2024.html", "text", "Clean Energy Businesses were sold in March 2023.",
                Map.of("company", "ED", "year", "2024", "item", "Item 8"), 0.9)
                .withMatchedTask("audit");
        var opinion = new FinancialRagService.FinancialChunk(
                "opinion", "ED_2024.html", "text",
                "Opinions on the Financial Statements and Internal Control over Financial Reporting. "
                        + "In our opinion, the statements present fairly in conformity with GAAP. "
                        + "Also in our opinion, the Company maintained effective internal control over financial reporting.",
                Map.of("company", "ED", "year", "2024", "item", "Item 8"), 0.8)
                .withMatchedTask("audit");

        assertThat(service.evidenceSupport(audit, unrelated))
                .isEqualTo(FinancialRagService.EvidenceSupport.SCOPE_ONLY);
        assertThat(service.evidenceSupport(audit, opinion))
                .isEqualTo(FinancialRagService.EvidenceSupport.DIRECT_SUPPORT);
        assertThat(service.missingDirectSupport(List.of(audit), List.of(unrelated)))
                .singleElement().asString().contains("missing_direct_support", "audit_opinion");
    }

    @Test
    void prefersCompleteParentAuditOpinionOverSubsidiaryOnlyOpinion() {
        var task = new FinancialRagService.FinancialRetrievalTask(
                "audit", "AEP FY2024 audit opinion", List.of("AEP"), List.of("2024"),
                "audit_opinion", "retrieve", "text", List.of());
        String subsidiaryContent = "Report of Independent Registered Public Accounting Firm. "
                + "Opinion on the Financial Statements. AEP Transmission Company, LLC. "
                + "In our opinion, the statements present fairly. PCAOB.";
        String parentContent = "Report of Independent Registered Public Accounting Firm. "
                + "Opinions on the Financial Statements and Internal Control over Financial Reporting. "
                + "We also have audited internal control over financial reporting. In our opinion, the statements "
                + "present fairly. Also in our opinion, the Company maintained effective internal control. "
                + "PCAOB; required to be independent.";
        var subsidiary = new FinancialRagService.FinancialChunk(
                "subsidiary", "AEP_2024.html", "text", subsidiaryContent,
                Map.of("company", "AEP", "year", "2024", "item", "Item 8"), 0.95)
                .withMatchedTask("audit");
        var parent = new FinancialRagService.FinancialChunk(
                "parent", "AEP_2024.html", "text", parentContent,
                Map.of("company", "AEP", "year", "2024", "item", "Item 8"), 0.80)
                .withMatchedTask("audit");

        assertThat(service.preferCompleteAuditEvidence(task, List.of(subsidiary, parent)))
                .extracting("chunkId").containsExactly("parent", "subsidiary");
        assertThat(service.auditEvidenceCompletenessScore(parentContent))
                .isGreaterThan(service.auditEvidenceCompletenessScore(subsidiaryContent));
        assertThat(service.evidenceSupport(task, subsidiary))
                .isEqualTo(FinancialRagService.EvidenceSupport.SCOPE_ONLY);
        assertThat(service.evidenceSupport(task, parent))
                .isEqualTo(FinancialRagService.EvidenceSupport.DIRECT_SUPPORT);
        assertThat(service.bestCompleteAuditOpinionCandidate(
                task, "Item 8", List.of(subsidiary, parent))).isSameAs(parent);
        assertThat(service.duplicatesSelectedAuditTask(subsidiary, List.of(parent), List.of(task))).isTrue();
        assertThat(service.duplicatesSelectedAuditTask(parent, List.of(), List.of(task))).isFalse();
    }

    @Test
    void keepsAuditOpinionAnswerScopedToThePlannerTopic() {
        var aepTask = new FinancialRagService.FinancialRetrievalTask(
                "audit_aep", "AEP audit opinion", List.of("AEP"), List.of("2024"),
                "audit_opinion", "retrieve", "text", List.of());
        var edTask = new FinancialRagService.FinancialRetrievalTask(
                "audit_ed", "ED audit opinion", List.of("ED"), List.of("2024"),
                "audit_opinion", "retrieve", "text", List.of());
        var plan = new FinancialRagService.FinancialRetrievalPlan(
                "Compare AEP and ED audit opinions", "Compare AEP and ED audit opinions", "comparison",
                List.of(), List.of(aepTask, edTask));
        var ledger = new FinancialEvidenceLedger.Ledger(List.of(), List.of(), List.of());

        assertThat(service.queryIntent(plan))
                .extracting(FinancialRagService.QueryIntent::companies,
                        FinancialRagService.QueryIntent::years,
                        FinancialRagService.QueryIntent::requestedTopics,
                        FinancialRagService.QueryIntent::needsComparison,
                        FinancialRagService.QueryIntent::needsCalculation)
                .containsExactly(List.of("AEP", "ED"), List.of("2024"), List.of("audit_opinion"), true, false);
        assertThat(service.answerContract(plan, ledger))
                .contains("Minimum-sufficient comparison", "requestedTopics=[audit_opinion]")
                .doesNotContain("GAAP", "COSO", "PCAOB");
    }

    @Test
    void doesNotApplyFullAuditOpinionContractToIndependenceResponsibilityQuestion() {
        var aep = new FinancialRagService.FinancialRetrievalTask(
                "aep_independence", "AEP auditor independence and responsibility", List.of("AEP"), List.of("2024"),
                "auditor independence and responsibility", "retrieve", "text", List.of());
        var xel = new FinancialRagService.FinancialRetrievalTask(
                "xel_independence", "XEL auditor independence and responsibility", List.of("XEL"), List.of("2024"),
                "auditor independence and responsibility", "retrieve", "text", List.of());
        var plan = new FinancialRagService.FinancialRetrievalPlan(
                "Compare auditor independence", "Compare auditor independence", "comparison",
                List.of(), List.of(aep, xel));
        var ledger = new FinancialEvidenceLedger.Ledger(List.of(), List.of(), List.of());

        assertThat(service.isAuditOpinionComparison(plan)).isFalse();
        assertThat(service.answerContract(plan, ledger))
                .contains("Minimum-sufficient comparison", "auditor independence and responsibility")
                .doesNotContain("internal control", "COSO");
        assertThat(service.preferCompleteAuditEvidence(aep, List.of())).isEmpty();
    }

    @Test
    void guaranteeComparisonContractExcludesUnrequestedDollarDetails() {
        var aep = new FinancialRagService.FinancialRetrievalTask(
                "aep", "AEP guarantees and indemnifications", List.of("AEP"), List.of("2024"),
                "guarantees and indemnifications disclosure", "retrieve", "text", List.of());
        var xel = new FinancialRagService.FinancialRetrievalTask(
                "xel", "XEL guarantees and indemnifications", List.of("XEL"), List.of("2024"),
                "guarantees and indemnifications disclosure", "retrieve", "text", List.of());
        var plan = new FinancialRagService.FinancialRetrievalPlan(
                "Compare guarantees", "Compare guarantees", "comparison", List.of(), List.of(aep, xel));

        assertThat(service.answerContract(plan,
                new FinancialEvidenceLedger.Ledger(List.of(), List.of(), List.of())))
                .contains("Minimum-sufficient comparison", "guarantees and indemnifications disclosure",
                        "Do not add adjacent");
    }

    @Test
    void repairsStaleItemMetadataAndConsolidatesComplementaryAuditStandards() {
        var task = new FinancialRagService.FinancialRetrievalTask(
                "audit_ed", "ED FY2024 Item 8 audit opinion", List.of("ED"), List.of("2024"),
                "audit_opinion", "retrieve", "text", List.of());
        String primaryText = "Opinions on the Financial Statements and Internal Control over Financial Reporting. "
                + "In our opinion, the statements present fairly under GAAP. Also in our opinion, the Company "
                + "maintained effective internal control over financial reporting based on COSO criteria.";
        String standardsText = "Our responsibility is to express opinions on the Company's consolidated financial "
                + "statements and internal control over financial reporting. We conducted our audits in accordance "
                + "with the standards of the PCAOB, including whether effective internal control was maintained.";
        var primary = new FinancialRagService.FinancialChunk(
                "primary", "ED_2024.html", "text", primaryText,
                Map.of("company", "ED", "year", "2024", "item", "Item 8"), 0.9)
                .withMatchedTask(task.id());
        var staleItemStandards = new FinancialRagService.FinancialChunk(
                "standards", "ED_2024.html", "text", standardsText,
                Map.of("company", "ED", "year", "2024", "item", "Item 1A"), 0.8)
                .withMatchedTask(task.id());

        assertThat(service.canonicalItem(staleItemStandards)).isEqualTo("Item 8");
        assertThat(service.auditStandardsPassage(standardsText)).isTrue();
        assertThat(service.enrichAuditEvidence(task, primary, List.of(primary, staleItemStandards)))
                .extracting("content").asString()
                .contains(primaryText, "Complementary audit-standards passage", "standards of the PCAOB");
        String oversized = primaryText + " background".repeat(250)
                + "\n\nComplementary audit-standards passage:\n" + standardsText;
        assertThat(service.compactAuditEvidence(oversized, task.query(), 1800))
                .contains("present fairly", "effective internal control", "standards of the PCAOB")
                .hasSizeLessThanOrEqualTo(1800);
    }

    @Test
    void balancesEachFiscalYearInsideOnePlannerTask() {
        var task = new FinancialRagService.FinancialRetrievalTask("headers", "AAA table headers",
                List.of("AAA"), List.of("2022", "2024"), "table headers", "retrieve", "table", List.of());
        var newer = new FinancialRagService.FinancialChunk("new", "AAA_2024.html", "table",
                "Table header: 2024 | 2023 | 2022", Map.of("company", "AAA", "year", "2024"), 0.9)
                .withMatchedTask("headers");
        var duplicateYear = new FinancialRagService.FinancialChunk("new2", "AAA_2024.html", "table",
                "Table header: 2024 | 2023", Map.of("company", "AAA", "year", "2024"), 0.8)
                .withMatchedTask("headers");
        var older = new FinancialRagService.FinancialChunk("old", "AAA_2022.html", "table",
                "Table header: 2022 | 2021 | 2020", Map.of("company", "AAA", "year", "2022"), 0.7)
                .withMatchedTask("headers");
        var selected = service.verifyAndSelectEvidence(new FinancialRagService.FinancialRetrievalPlan(
                "question", "question", "comparison", List.of(), List.of(task)),
                List.of(newer, duplicateYear, older), 2, 2);
        assertThat(selected).extracting("sourceFile")
                .containsExactlyInAnyOrder("AAA_2022.html", "AAA_2024.html");
        assertThat(service.atomicRetrievalScopes(List.of(task))).hasSize(2)
                .allSatisfy(scope -> assertThat(scope.id()).isEqualTo("headers"));
    }

    @Test
    void rejectsOrphanNumericRowsAndPreservesFullTableOperands() {
        var task = new FinancialRagService.FinancialRetrievalTask("retrieve_total",
                "AAA FY2024 total revenues", List.of("AAA"), List.of("2024"),
                "Total revenues", "retrieve", "table", List.of());
        var orphan = new FinancialRagService.FinancialChunk("orphan", "AAA_2024.html", "text",
                "Section: Item 7\n| Revenues | 300 | 200 |",
                Map.of("company", "AAA", "year", "2024", "item", "Item 7"), 0.9);
        assertThat(service.evidenceMatchesTaskScope(task, orphan)).isFalse();
        String table = "Table context before:\n" + "Irrelevant narrative. ".repeat(150)
                + "\nTable:\nTable header: 2024 | 2023 | (in thousands)\n"
                + "| Total revenues | 900 | 800 |\n| Operating income | 90 | 80 |\n";
        String compact = service.compactMetricTable(table, List.of(task), 500);
        assertThat(compact).contains("Table header: 2024 | 2023 | (in thousands)",
                "| Total revenues | 900 | 800 |").doesNotContain("Irrelevant", "Operating income");
        assertThat(compact.length()).isLessThanOrEqualTo(500);
    }

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
    void parsesTypedCalculationPlanWithoutInferringFromQuestionKeywords() {
        FinancialRagService.FinancialRetrievalPlan plan = service.parseRetrievalPlan(
                "compare the totals", """
                        {
                          "resolvedQuestion":"Compare AAPL and NVDA totals",
                          "intent":"calculation",
                          "subTasks":[
                            {"id":"retrieve_aapl","query":"AAPL total net sales","companies":["AAPL"],
                             "years":["2024"],"metric":"total net sales","operation":"retrieve","modality":"table","dependsOn":[]},
                            {"id":"retrieve_nvda","query":"NVDA total revenue","companies":["NVDA"],
                             "years":["2024"],"metric":"total revenue","operation":"retrieve","modality":"table","dependsOn":[]}
                          ],
                          "calculationPlans":[{
                            "id":"calculate_multiple","operator":"RATIO",
                            "operands":[
                              {"role":"numerator","sourceTaskId":"retrieve_aapl","company":"AAPL","fiscalYear":"2024","metric":"total net sales",
                               "rawValue":"$391.0 million","unit":"usd","scale":"million"},
                              {"role":"denominator","sourceTaskId":"retrieve_nvda","company":"NVDA","fiscalYear":"2024","metric":"total revenue"}
                            ],
                            "outputUnit":"times","outputScale":"unit","precision":1,"periods":0
                          }],
                          "retrievalQueries":["AAPL and NVDA revenue"]
                        }
                        """);

        assertThat(plan.calculationPlans()).hasSize(1);
        FinancialEvidenceLedger.CalculationPlan calculation = plan.calculationPlans().get(0);
        assertThat(calculation.operator()).isEqualTo(FinancialEvidenceLedger.CalculationOperator.RATIO);
        assertThat(calculation.operands()).extracting(FinancialEvidenceLedger.CalculationOperand::sourceTaskId)
                .containsExactly("retrieve_aapl", "retrieve_nvda");
        assertThat(calculation.operands().get(0))
                .extracting(FinancialEvidenceLedger.CalculationOperand::rawValue,
                        FinancialEvidenceLedger.CalculationOperand::unit,
                        FinancialEvidenceLedger.CalculationOperand::scale)
                .containsExactly("$391.0 million", "usd", "million");
        assertThat(calculation.outputUnit()).isEqualTo("times");
        assertThat(calculation.precision()).isEqualTo(1);
        assertThat(service.calculationFactQuestion(plan))
                .contains("Required typed calculation operands", "RATIO", "retrieve_aapl", "retrieve_nvda");
    }

    @Test
    void forcesEveryRetrieveTaskToExplicitSingleCompanyScope() {
        FinancialRagService.FinancialRetrievalTask hallucinated = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_other", "global revenue", List.of("MSFT"), List.of("2024"),
                "revenue", "retrieve", "table", List.of());

        FinancialRagService.FinancialRetrievalTask scoped =
                service.enforceSingleCompanyScope(hallucinated, "AAPL");

        assertThat(scoped.companies()).containsExactly("AAPL");
        assertThat(scoped.years()).containsExactly("2024");
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
    void fallbackPlanKeepsDatasetScopeOutOfSemanticQueries() {
        FinancialRagService.FinancialRetrievalPlan plan = service.parseRetrievalPlan("""
                What differences exist between AEP and ED's strategic objectives in FY2024?
                Dataset retrieval scope (metadata only): documentScopes=AEP FY2024; ED FY2024
                """, "");

        assertThat(plan.resolvedQuestion())
                .isEqualTo("What differences exist between AEP and ED's strategic objectives in FY2024?");
        assertThat(plan.queries())
                .allMatch(query -> !query.toLowerCase().contains("dataset retrieval scope"));
        assertThat(plan.subTasks())
                .extracting(FinancialRagService.FinancialRetrievalTask::query)
                .allMatch(query -> !query.toLowerCase().contains("dataset retrieval scope"));

        FinancialRagService.FinancialRetrievalTask pollutedPlannerTask =
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_aep", "AEP disclaimer Dataset retrieval scope (metadata only): AEP FY2024",
                        List.of("AEP"), List.of("2024"), "forward-looking disclaimer",
                        "retrieve", "text", List.of());
        assertThat(service.retrievalQueryForTask(pollutedPlannerTask))
                .isEqualTo("AEP disclaimer forward-looking disclaimer");
    }

    @Test
    void ignoresPlannerCountPlansForTextTermEnumeration() {
        FinancialRagService.FinancialRetrievalPlan plan = service.parseRetrievalPlan(
                "How many distinct financial statement items are mentioned? List the items.", """
                        {
                          "resolvedQuestion":"How many distinct financial statement items are mentioned? List the items.",
                          "intent":"enumeration",
                          "subTasks":[{
                            "id":"retrieve_terms","query":"Item 7 description terms","companies":["LLY"],
                            "years":["2022"],"metric":"description terms","operation":"retrieve",
                            "modality":"text","dependsOn":[]
                          }],
                          "calculationPlans":[{
                            "id":"count_years","operator":"COUNT","operands":[
                              {"role":"component","sourceTaskId":"retrieve_terms","company":"LLY",
                               "fiscalYear":"2022","metric":"description terms"}
                            ],"outputUnit":"count","outputScale":"unit","precision":0,"periods":0
                          }],
                          "retrievalQueries":["Item 7 description terms"]
                        }
                        """);

        assertThat(plan.calculationPlans()).isEmpty();
    }

    @Test
    void completesMissingAdjacentPercentageChangePlanFromPlannerTypedTasks() {
        List<FinancialRagService.FinancialRetrievalTask> tasks = List.of(
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_2022", "net income 2022", List.of("LLY"), List.of("2022"),
                        "net income", "retrieve", "table", List.of()),
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_2023", "net income 2023", List.of("LLY"), List.of("2023"),
                        "net income", "retrieve", "table", List.of()),
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_2024", "net income 2024", List.of("LLY"), List.of("2024"),
                        "net income", "retrieve", "table", List.of()));
        FinancialEvidenceLedger.CalculationPlan plannerPlan = new FinancialEvidenceLedger.CalculationPlan(
                "change_2023_2024", FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                List.of(
                        new FinancialEvidenceLedger.CalculationOperand(
                                "start", "retrieve_2023", "LLY", "2023", "net income"),
                        new FinancialEvidenceLedger.CalculationOperand(
                                "end", "retrieve_2024", "LLY", "2024", "net income")),
                "percent", "unit", 1, 0);

        List<FinancialEvidenceLedger.CalculationPlan> completed =
                service.completeAdjacentPercentageChangePlans(tasks, List.of(plannerPlan));

        assertThat(completed).hasSize(2);
        assertThat(completed).anyMatch(plan -> plan.operands().stream()
                .map(FinancialEvidenceLedger.CalculationOperand::sourceTaskId)
                .toList().equals(List.of("retrieve_2022", "retrieve_2023")));
    }

    @Test
    void synthesizesAdjacentPercentagePlansFromStructuredTrendIntent() {
        List<FinancialRagService.FinancialRetrievalTask> tasks = List.of(
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_2022", "LLY net income 2022", List.of("LLY"), List.of("2022"),
                        "net income", "retrieve", "hybrid", List.of()),
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_2023", "LLY net income 2023", List.of("LLY"), List.of("2023"),
                        "net income", "retrieve", "hybrid", List.of()),
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_2024", "LLY net income 2024", List.of("LLY"), List.of("2024"),
                        "net income", "retrieve", "hybrid", List.of()));

        List<FinancialEvidenceLedger.CalculationPlan> plans =
                service.completeTrendCalculationPlans("trend", tasks, List.of());

        assertThat(plans).hasSize(2)
                .allMatch(plan -> plan.operator() == FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE);
        assertThat(plans).extracting(FinancialEvidenceLedger.CalculationPlan::id)
                .containsExactly("calculate_trend_lly_2022_2023", "calculate_trend_lly_2023_2024");
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
    void expandsUnscopedFallbackRetrievalAcrossEveryExplicitDocumentScope() {
        FinancialRagService.FinancialRetrievalTask fallback =
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_1", "compare forward-looking language in Item 7",
                        List.of(), List.of("2022", "2024"), "", "retrieve", "hybrid", List.of());

        List<FinancialRagService.FinancialRetrievalTask> scoped = service.scopeFallbackRetrievalTasks(
                List.of(fallback), List.of(
                        new FinancialRagService.CompanyYearScope("LLY", "2022"),
                        new FinancialRagService.CompanyYearScope("LLY", "2024"),
                        new FinancialRagService.CompanyYearScope("PFE", "2022"),
                        new FinancialRagService.CompanyYearScope("PFE", "2024")));

        assertThat(scoped).extracting(FinancialRagService.FinancialRetrievalTask::id)
                .containsExactly("retrieve_scope_lly_2022", "retrieve_scope_lly_2024",
                        "retrieve_scope_pfe_2022", "retrieve_scope_pfe_2024");
        assertThat(scoped).allMatch(task -> task.companies().size() == 1 && task.years().size() == 1);
    }

    @Test
    void splitsCompanyBoundFallbackAcrossExplicitYears() {
        FinancialRagService.FinancialRetrievalTask fallback =
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_1", "net income change", List.of("LLY"),
                        List.of("2022", "2023", "2024"), "", "retrieve", "hybrid", List.of());

        List<FinancialRagService.FinancialRetrievalTask> scoped = service.scopeFallbackRetrievalTasks(
                List.of(fallback), List.of(
                        new FinancialRagService.CompanyYearScope("LLY", "2022"),
                        new FinancialRagService.CompanyYearScope("LLY", "2023"),
                        new FinancialRagService.CompanyYearScope("LLY", "2024")));

        assertThat(scoped).extracting(FinancialRagService.FinancialRetrievalTask::id)
                .containsExactly("retrieve_scope_lly_2022", "retrieve_scope_lly_2023", "retrieve_scope_lly_2024");
        assertThat(scoped).allMatch(task -> task.companies().size() == 1 && task.years().size() == 1);
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

        FinancialRagService.FinancialRetrievalTask objectiveTask =
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_ed_objective", "ED FY2024 primary strategic objective",
                        List.of("ED"), List.of("2024"), "strategy", "retrieve", "hybrid", List.of());
        assertThat(service.effectiveTaskModality(objectiveTask)).isEqualTo("text");
    }

    @Test
    void treatsDatasetItemAsSoftScopeForExecutiveBiographyFacts() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_lamba_2022", "Sanjiv Lamba age executive officer Item 7",
                List.of("LIN"), List.of("2022"), "executive_age",
                "retrieve", "text", List.of());

        assertThat(service.requestedItemForTask(task)).isBlank();
        assertThat(service.retrievalQueryForTask(task))
                .contains("Sanjiv Lamba", "executive_age")
                .doesNotContainIgnoringCase("Item 7");
    }

    @Test
    void retainsExplicitItemScopeForSectionSpecificQuestions() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_ed_objective", "ED primary strategic objective in Item 7",
                List.of("ED"), List.of("2024"), "strategic_objective",
                "retrieve", "text", List.of());

        assertThat(service.requestedItemForTask(task)).isEqualTo("Item 7");
        assertThat(service.retrievalQueryForTask(task)).contains("Item 7");
    }

    @Test
    void treatsFilingCrossReferencesAsSoftSectionScope() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_lin_segment_note",
                "LIN FY2024 Item 7 references to financial tables for sales and reportable segment detail",
                List.of("LIN"), List.of("2024"), "table references for sales data",
                "retrieve", "text", List.of());

        assertThat(service.isFilingCrossReferenceTask(task)).isTrue();
        assertThat(service.requestedItemForTask(task)).isBlank();
        assertThat(service.retrievalQueryForTask(task)).doesNotContainIgnoringCase("Item 7");
        assertThat(service.semanticAnchorQueries(service.retrievalQueryForTask(task)))
                .anyMatch(query -> query.contains("consolidated sales")
                        && query.contains("additional information") && query.contains("reportable segments"));

        FinancialRagService.FinancialRetrievalTask plannerWording =
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_lin_note", "LIN FY2024 Item 7 table note reference additional segment detail",
                        List.of("LIN"), List.of("2024"), "segment table or note reference",
                        "retrieve", "hybrid", List.of());
        assertThat(service.isFilingCrossReferenceTask(plannerWording)).isTrue();
    }

    @Test
    void recognizesExactSegmentNoteCrossReferenceEvidence() {
        assertThat(service.filingCrossReferenceEvidenceMatches(
                "Refer to Item 7 for a discussion of consolidated sales and Note 18 to the consolidated "
                        + "financial statements for additional information related to Linde's reportable segments."))
                .isTrue();
        assertThat(service.filingCrossReferenceEvidenceMatches(
                "Note 3 contains cost reduction program and other charges."))
                .isFalse();
    }

    @Test
    void detectsCrossParentNearDuplicatesWithoutCollapsingDifferentFinancialRows() {
        String first = "Sales grew two percent from higher price attainment while volumes remained flat. "
                + "Cost pass-through decreased sales and currency translation also decreased sales. "
                + "Operating profit increased because of pricing and productivity initiatives across the segment.";
        String overlapping = "Sales grew two percent from higher price attainment while volumes remained flat. "
                + "Cost pass-through decreased sales and currency translation also decreased sales. "
                + "Operating profit increased because of pricing and productivity initiatives across the segment. "
                + "Additional discussion follows in the annual report.";
        String differentRow = "Net income declined from 450 million in 2023 to 310 million in 2024. "
                + "Interest expense and restructuring charges were the primary causes of the change. "
                + "Management expects capital expenditure to remain stable during the following fiscal year.";

        assertThat(service.nearDuplicateText(first, overlapping)).isTrue();
        assertThat(service.nearDuplicateText(first, differentRow)).isFalse();
    }

    @Test
    void removesDatasetRoutingSuffixBeforeGlobalReranking() {
        assertThat(service.stripDatasetRetrievalScope("""
                How did Sanjiv Lamba's age change?

                Dataset retrieval scope (metadata only): documentScopes=LIN FY2022; evidence section=Item 7.
                """))
                .isEqualTo("How did Sanjiv Lamba's age change?");
    }

    @Test
    void recognizesANameImmediatelyFollowedByAnExecutiveAge() {
        assertThat(service.biographicalEvidenceMatches(
                "Sanjiv Lamba age executive officer",
                "Executive Officers. Sanjiv Lamba, 60, was appointed Chief Executive Officer."))
                .isTrue();
        assertThat(service.biographicalEvidenceMatches(
                "Sanjiv Lamba age executive officer",
                "An exhibit signed by Sanjiv Lamba on March 1, 2022."))
                .isFalse();
    }

    @Test
    void recognizesGenericExecutiveOfficerListingsWithNameAndAgeRows() {
        assertThat(service.biographicalEvidenceMatches(
                "Linde FY2022 executive officers listing names positions",
                "Executive Officers. Sanjiv Lamba, 58, Chief Executive Officer; Guillermo Bichara, 48, officer."))
                .isTrue();
        assertThat(service.biographicalEvidenceMatches(
                "Linde FY2022 executive officers listing names positions",
                "Consolidated results: net income was $6,244.8 million."))
                .isFalse();
    }

    @Test
    void deterministicallyAnswersCrossYearExecutiveAgeFromExactEvidence() {
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(List.of(
                new FinancialEvidenceLedger.EvidenceDocument(
                        "E1", "c1", "LIN_2022.html", "LIN", "2022", "text", "Item 1", "BUSINESS",
                        "Sanjiv Lamba, 58, was appointed Chief Executive Officer.", List.of("age_2022")),
                new FinancialEvidenceLedger.EvidenceDocument(
                        "E2", "c2", "LIN_2023.html", "LIN", "2023", "text", "Item 1", "BUSINESS",
                        "Sanjiv Lamba, 59, was appointed Chief Executive Officer.", List.of("age_2023")),
                new FinancialEvidenceLedger.EvidenceDocument(
                        "E3", "c3", "LIN_2024.html", "LIN", "2024", "text", "Item 1", "BUSINESS",
                        "Sanjiv Lamba, 60, was appointed Chief Executive Officer.", List.of("age_2024"))
        ), List.of(), List.of());

        assertThat(service.deterministicBiographicalAgeAnswer(
                "How did the age of Sanjiv Lamba change between FY2022 and FY2023, and is it provided in FY2024?",
                ledger).orElseThrow())
                .contains("FY2022", "58 years old", "FY2023", "59 years old", "an increase of 1 year",
                        "FY2024", "60 years old", "[E1]", "[E2]", "[E3]");
    }

    @Test
    void detectsRoundedFinancialPremiseThatNeedsAnchoredRetrieval() {
        assertThat(service.needsAnchoredScopeRetrieval(
                "DIS FY2024 net income is $5.0 billion, an increase of $2.6 billion from the prior year."))
                .isTrue();
        assertThat(service.needsAnchoredScopeRetrieval("Compare AMD and Microsoft cash balances."))
                .isFalse();
    }

    @Test
    void skipsNumericalFactExtractionForQualitativeAndTextEnumerationQuestions() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                new FinancialEvidenceLedger.EvidenceDocument(
                        "E1", "c1", "LLY_2024.html", "LLY", "2024", "text", "Item 7", "MD&A",
                        "Item 7 discusses actual results, financial position, and cash generated from operations in 2024.",
                        List.of("retrieve_lly_2024_item7")));
        FinancialRagService.FinancialRetrievalPlan qualitative = new FinancialRagService.FinancialRetrievalPlan(
                "Compare objectives", "Compare the primary strategic objectives of AEP and ED.",
                "comparison", List.of(), List.of());
        FinancialRagService.FinancialRetrievalPlan enumeration = new FinancialRagService.FinancialRetrievalPlan(
                "Count items", "How many distinct financial statement items are mentioned? List the items.",
                "comparison", List.of(), List.of());
        FinancialRagService.FinancialRetrievalPlan numeric = new FinancialRagService.FinancialRetrievalPlan(
                "Revenue", "How much revenue was reported in 2024?", "factual_metric", List.of(), List.of());
        FinancialRagService.FinancialRetrievalPlan lineItemIdentification =
                new FinancialRagService.FinancialRetrievalPlan(
                        "Identify a line item",
                        "Which financial statement line item would quantify cash generated from operations?",
                        "comparison", List.of(), List.of());
        FinancialRagService.FinancialRetrievalPlan rewrittenLineItemQuestion =
                new FinancialRagService.FinancialRetrievalPlan(
                        "Which financial statement line item would quantify cash generated from operations?",
                        "What wording changes occurred, and which specific line item in the Consolidated Statements "
                                + "of Cash Flows would quantify the risk?",
                        "comparison", List.of(), List.of());

        assertThat(service.needsEvidenceFacts(qualitative, evidence)).isFalse();
        assertThat(service.needsEvidenceFacts(enumeration, evidence)).isFalse();
        assertThat(service.needsEvidenceFacts(lineItemIdentification, evidence)).isFalse();
        assertThat(service.needsEvidenceFacts(rewrittenLineItemQuestion, evidence)).isFalse();
        assertThat(service.needsEvidenceFacts(numeric, evidence)).isTrue();
    }

    @Test
    void plannerRewriteCannotTurnQualitativeNonGaapComparisonIntoFactExtraction() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                new FinancialEvidenceLedger.EvidenceDocument(
                        "E1", "c1", "XEL_2024.html", "XEL", "2024", "table", "Item 7", "Non-GAAP",
                        "GAAP net income was $1,936 million and ongoing earnings were $1,969 million.",
                        List.of("retrieve_xel")));
        FinancialRagService.FinancialRetrievalPlan qualitative = new FinancialRagService.FinancialRetrievalPlan(
                "Compare how AEP and XEL discuss non-GAAP financial measures.",
                "Compare non-GAAP measures including the net income reconciliation amounts.",
                "comparison", List.of(), List.of());

        assertThat(service.needsEvidenceFacts(qualitative, evidence)).isFalse();
    }

    @Test
    void expandsItemSevenDescriptionQueriesWithStandardMdAndATerms() {
        String expanded = service.expandQueryTerms(
                "PFE FY2024 description of Item 7 financial statement items mentioned");

        assertThat(expanded)
                .contains("actual results", "financial position", "financial condition")
                .contains("results of operations", "cash generated from operations", "cash flows from operations");
    }

    @Test
    void createsFocusedSemanticAnchorsForDescriptionsAndObjectives() {
        assertThat(service.semanticAnchorQueries(
                "PFE FY2024 description of Item 7 financial statement items mentioned"))
                .anyMatch(query -> query.contains("actual results") && query.contains("cash generated"))
                .anyMatch(query -> query.contains("financial condition") && query.contains("cash flows"));
        assertThat(service.semanticAnchorQueries(
                "ED FY2024 Item 7 primary strategic objectives"))
                .anyMatch(query -> query.contains("Item 7")
                        && query.contains("shareholder value") && query.contains("dividend growth"))
                .anyMatch(query -> query.contains("forward-looking statements") && query.contains("risks"));
        assertThat(service.semanticAnchorQueries(
                "ED FY2024 Item 7 forward-looking statement disclaimer safe harbor"))
                .anyMatch(query -> query.contains("external business environment")
                        && query.contains("competitive landscape"));
        assertThat(service.semanticAnchorQueries(
                "ECL FY2022 quantitative sales drivers volume and pricing"))
                .anyMatch(query -> query.contains("provide quantitative information")
                        && query.contains("material sales drivers"));
        assertThat(service.semanticAnchorQueries(
                "NFLX FY2024 paid net membership additions"))
                .anyMatch(query -> query.contains("consolidated performance highlights")
                        && query.contains("Global Streaming Memberships"));
    }

    @Test
    void promotesExactDescriptionSentenceAboveGenericItemSevenHeading() {
        FinancialRagService.FinancialChunk heading = new FinancialRagService.FinancialChunk(
                "heading", "LLY_2022.html", "text",
                "Management's Discussion and Analysis of Results of Operations and Financial Condition",
                Map.of("company", "LLY", "year", "2022", "item", "Item 7"), 0.95);
        FinancialRagService.FinancialChunk description = new FinancialRagService.FinancialChunk(
                "description", "LLY_2022.html", "text",
                "Risks may cause our actual results, financial position, and cash generated from operations to differ.",
                Map.of("company", "LLY", "year", "2022", "item", "Item 7"), 0.60);

        List<FinancialRagService.FinancialChunk> promoted = service.promoteTextEnumerationEvidence(
                "How many distinct financial statement items are mentioned? List the items.",
                List.of(heading, description));

        assertThat(promoted).containsExactly(description, heading);
    }

    @Test
    void derivesConjunctiveExactPhrasesFromSemanticAnchors() {
        assertThat(service.exactAnchorPhrases(
                "Item 7 forward-looking statements actual results financial position cash generated from operations"))
                .containsExactly("actual results", "financial position", "cash generated from operations");
        assertThat(service.exactAnchorPhrases(
                "Item 7 MD&A financial condition results of operations cash flows from operations outside sources"))
                .containsExactly("financial condition", "results of operations", "cash flows from operations");
        assertThat(service.exactAnchorPhrases(
                "provide quantitative information material sales drivers changes volume and pricing"))
                .containsExactly("provide quantitative information", "material sales drivers", "volume and pricing");
        assertThat(service.exactAnchorPhrases(
                "explicit strategic objective shareholder value dividend growth earnings growth"))
                .containsExactly("shareholder value", "dividend growth", "earnings growth");
        assertThat(service.exactAnchorPhrases(
                "consolidated sales consolidated financial statements additional information related to reportable segments"))
                .containsExactly("consolidated sales", "additional information", "reportable segments");
        assertThat(service.exactAnchorPhrases(
                "consolidated performance highlights Global Streaming Memberships paid net membership additions"))
                .containsExactly("consolidated performance highlights", "global streaming memberships",
                        "paid net membership additions");
    }

    @Test
    void extractsRequestedFilingItemForExactPhrasePreference() {
        assertThat(service.requestedItem("ED FY2024 Item 7 primary objective")).isEqualTo("Item 7");
        assertThat(service.requestedItem("META Item 7A market risk")).isEqualTo("Item 7A");
        assertThat(service.requestedItem("FY2024 filing objective")).isBlank();
    }

    @Test
    void normalizesMislabelledNarrativeDescriptionToCanonicalItemSeven() {
        assertThat(service.canonicalItem(
                "Item 1A", "text", "Risk Factors",
                "The MD&A discusses financial condition, results of operations, and cash flows from operations."))
                .isEqualTo("Item 7");
        assertThat(service.canonicalItem(
                "Item 1A", "text", "Risk Factors",
                "ITEM 1A. Risk Factors. The MD&A discusses financial condition, results of operations, "
                        + "and cash flows from operations."))
                .isEqualTo("Item 7");
        assertThat(service.canonicalItem(
                "Item 8", "table", "Financial Statements",
                "financial condition, results of operations, and cash flows from operations"))
                .isEqualTo("Item 8");
        assertThat(service.canonicalItem(
                "Item 1", "text", "ITEM 7. Management's Discussion and Analysis", "Opening paragraph"))
                .isEqualTo("Item 7");
    }

    @Test
    void deterministicallyAnswersDescriptionItemEnumerationFromLiteralEvidence() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                descriptionEvidence("E1", "LLY", "2022",
                        "actual results, financial position, and cash generated from operations"),
                descriptionEvidence("E2", "LLY", "2024",
                        "actual results, financial position, and cash generated from operations"),
                descriptionEvidence("E3", "PFE", "2022",
                        "financial condition, results of operations, and cash flows from operations"),
                descriptionEvidence("E4", "PFE", "2024",
                        "financial condition, results of operations, and cash flows from operations"));
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                evidence, List.of(), List.of());

        String answer = service.deterministicTextEnumerationAnswer(
                        "How many distinct financial statement items are mentioned in the description of Item 7 "
                                + "for each company? Provide the counts and list the items.", ledger)
                .orElseThrow();

        assertThat(answer)
                .contains("LLY: 3 distinct items", "'actual results'", "[E1][E2]")
                .contains("PFE: 3 distinct items", "'cash flows from operations'", "[E3][E4]")
                .contains("Total across all companies: 6 distinct terms")
                .doesNotContain("Revenue", "Net income");
        assertThat(new FinancialCitationVerifier().verify(answer, ledger).valid()).isTrue();
    }

    @Test
    void deterministicallyAnswersForwardLookingWordingComparison() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                descriptionEvidence("E1", "LLY", "2022",
                        "Results may differ materially from these forward-looking statements."),
                descriptionEvidence("E2", "LLY", "2024",
                        "Results may differ from these forward-looking statements."),
                descriptionEvidence("E3", "PFE", "2022",
                        "The following MD&A is intended to assist the reader in understanding our financial condition."),
                descriptionEvidence("E4", "PFE", "2024",
                        "The following MD&A is intended to assist the reader in understanding our financial condition."));
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                evidence, List.of(), List.of());

        String answer = service.deterministicForwardLookingComparisonAnswer(
                        "Compare the forward-looking language. What wording change occurs, and which line item "
                                + "quantifies cash generated from operations?", ledger)
                .orElseThrow();

        assertThat(answer)
                .contains("differ materially", "omits \"materially\"", "identical in 2022 and 2024")
                .contains("Net cash provided by operating activities", "[E1][E2]", "[E3][E4]");
        assertThat(new FinancialCitationVerifier().verify(answer, ledger).valid()).isTrue();
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
    void recognizesWhenRetrievalIsAlreadyBoundToCompanyAndYearScope() {
        FinancialRagService.FinancialRetrievalPlan scoped = new FinancialRagService.FinancialRetrievalPlan(
                "question", "question", "comparison", List.of("global expansion"), List.of(
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_lin", "LIN age", List.of("LIN"), List.of("2024"),
                        "age", "retrieve", "text", List.of())));
        FinancialRagService.FinancialRetrievalPlan unscoped = new FinancialRagService.FinancialRetrievalPlan(
                "question", "question", "factual", List.of("global expansion"), List.of(
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_any", "revenue", List.of(), List.of(),
                        "revenue", "retrieve", "hybrid", List.of())));

        assertThat(service.hasScopedRetrievalTask(scoped)).isTrue();
        assertThat(service.hasScopedRetrievalTask(unscoped)).isFalse();
    }

    @Test
    void keepsEnoughCandidatesForEveryScopedTaskBeforeGlobalReranking() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "hybridTopK", 60);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "rerankCandidateLimit", 24);

        assertThat(service.scopedRequestCandidateLimit(10)).isEqualTo(24);
        assertThat(service.scopedRequestCandidateLimit(30)).isEqualTo(30);
    }

    @Test
    void usesSmallerBudgetForSingleFactAndBalancedBudgetForMultiDocumentQuestions() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "hybridTopK", 60);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "rerankCandidateLimit", 24);
        FinancialRagService.FinancialRetrievalPlan single = new FinancialRagService.FinancialRetrievalPlan(
                "AAPL revenue", "AAPL revenue", "factual_metric", List.of(),
                List.of(retrievalTask("retrieve_aapl", "AAPL")));
        FinancialRagService.FinancialRetrievalPlan comparison = new FinancialRagService.FinancialRetrievalPlan(
                "compare", "compare", "comparison", List.of(), List.of(
                retrievalTask("retrieve_aapl", "AAPL"), retrievalTask("retrieve_nvda", "NVDA")));

        assertThat(service.retrievalPolicy(single, 10))
                .isEqualTo(new FinancialRagService.RetrievalPolicy(3, 12));
        assertThat(service.retrievalPolicy(comparison, 10))
                .isEqualTo(new FinancialRagService.RetrievalPolicy(4, 16));
    }

    @Test
    void rejectsOutOfItemEvidenceInsteadOfFallingBackToUnverifiedChunks() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_aapl_item7", "AAPL FY2024 Item 7 revenue", List.of("AAPL"), List.of("2024"),
                "revenue", "retrieve", "table", List.of());
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "question", "question", "factual_metric", List.of(), List.of(task));
        FinancialRagService.FinancialChunk wrongItem = new FinancialRagService.FinancialChunk(
                "chunk-1", "AAPL_2024.html", "table", "Revenue was $100 million.",
                Map.of("company", "AAPL", "year", "2024", "item", "Item 8"), 0.9)
                .withMatchedTask(task.id());

        assertThat(service.verifyAndSelectEvidence(plan, List.of(wrongItem), 8, 2)).isEmpty();
    }

    @Test
    void doesNotReportMissingItemScopeWhenAnotherAnchorCoveredTheTask() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_ed_item7", "ED FY2024 Item 7 disclaimer", List.of("ED"), List.of("2024"),
                "forward-looking disclaimer", "retrieve", "text", List.of());
        FinancialRagService.FinancialChunk covered = new FinancialRagService.FinancialChunk(
                "chunk-ed-item7", "ED_2024.html", "text", "Item 7 risk discussion",
                Map.of("company", "ED", "year", "2024", "item", "Item 7"), 0.8)
                .withMatchedTask(task.id());

        assertThat(service.missingScopedCoverage(List.of(task), List.of(covered))).isEmpty();
    }

    @Test
    void rejectsTotalMetricEvidenceWithoutTheNumericTotalRowForHybridRetrieval() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_aapl_total_sales", "AAPL FY2024 total net sales", List.of("AAPL"), List.of("2024"),
                "total net sales", "retrieve", "hybrid", List.of());
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "question", "question", "factual_metric", List.of(), List.of(task));
        FinancialRagService.FinancialChunk wrongRow = new FinancialRagService.FinancialChunk(
                "chunk-1", "AAPL_2024.html", "table", """
                | Net sales: |  |  |
                | Income before provision for income taxes | 123,485 | 113,736 |
                """, Map.of("company", "AAPL", "year", "2024", "item", "Item 8"), 0.9)
                .withMatchedTask(task.id());
        FinancialRagService.FinancialChunk totalRow = new FinancialRagService.FinancialChunk(
                "chunk-2", "AAPL_2024.html", "table",
                "| Total net sales | $ | 391,035 | $ | 383,285 |",
                Map.of("company", "AAPL", "year", "2024", "item", "Item 8"), 0.8)
                .withMatchedTask(task.id());

        List<FinancialRagService.FinancialChunk> selected = service.verifyAndSelectEvidence(
                plan, List.of(wrongRow, totalRow), 8, 2);

        assertThat(selected).hasSize(1);
        assertThat(service.evidenceMatchesTaskScope(task, selected.get(0))).isTrue();
        assertThat(service.evidenceMatchesTaskScope(task, wrongRow)).isFalse();
    }

    @Test
    void asksForConciseInScopeQualitativeComparisons() {
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "Compare AEP and ED", "Compare AEP and ED", "comparison", List.of("competition"), List.of(
                retrievalTask("retrieve_aep", "AEP"), retrievalTask("retrieve_ed", "ED")));
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(), List.of(), List.of());

        String answerPrompt = service.answerPrompt("Compare AEP and ED", plan, ledger);

        assertThat(answerPrompt).contains(
                "Required output language: English. This is mandatory",
                "Lightweight QueryIntent (qualitative generation scope only)",
                "Only answer the topics explicitly requested by the user",
                "Question-specific answer contract:",
                "Minimum-sufficient comparison",
                "Answer only requestedTopics",
                "Target 40-120 words",
                "Never calculate freely in prose")
                .doesNotContain("literal parallel noun phrases", "risk factors as implied objectives");
        assertThat(answerPrompt)
                .doesNotContain("Retrieval preprocessing:", "retrievalQueries:", "typedSubTasks:");
    }

    @Test
    void numericPromptUsesPlannerCoverageWithoutQueryIntentScope() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_2024", "LLY net income 2024", List.of("LLY"), List.of("2024"),
                "net income", "retrieve", "table", List.of());
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "How much net income?", "How much net income?", "factual_metric", List.of(), List.of(task));
        FinancialEvidenceLedger.FinancialFact fact = new FinancialEvidenceLedger.FinancialFact(
                "F1", "E1", "LLY", "2024", "net income", "$10 million",
                new java.math.BigDecimal("10"), "usd", "million", "$10 million");
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(evidence("E1", task.id())), List.of(fact), List.of());

        assertThat(service.answerPrompt("How much net income?", plan, ledger))
                .contains("Numeric answer contract", "Required retrieval coverage (from Query Planner)",
                        "every requested endpoint fact", "[F#][E#]")
                .doesNotContain("Lightweight QueryIntent", "requestedTopics");
    }

    @Test
    void qualitativeQuestionDoesNotUseNumericContractOnlyBecauseLedgerContainsFacts() {
        FinancialRagService.FinancialRetrievalTask aep = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_aep", "AEP non-GAAP measures", List.of("AEP"), List.of("2024"),
                "non-GAAP financial measures", "retrieve", "text", List.of());
        FinancialRagService.FinancialRetrievalTask xel = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_xel", "XEL non-GAAP measures", List.of("XEL"), List.of("2024"),
                "non-GAAP financial measures", "retrieve", "text", List.of());
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "Compare how AEP and XEL discuss non-GAAP financial measures.",
                "Compare how AEP and XEL discuss non-GAAP financial measures.",
                "comparison", List.of(), List.of(aep, xel));
        FinancialEvidenceLedger.FinancialFact incidental = new FinancialEvidenceLedger.FinancialFact(
                "F1", "E1", "XEL", "2024", "GAAP net income", "$1,936 million",
                new java.math.BigDecimal("1936"), "usd", "million", "$1,936 million");
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(evidence("E1", xel.id())), List.of(incidental), List.of());

        assertThat(service.usesNumericAnswerContract(
                "Compare how AEP and XEL discuss non-GAAP financial measures.", plan)).isFalse();
        assertThat(service.answerPrompt(
                "Compare how AEP and XEL discuss non-GAAP financial measures.", plan, ledger))
                .contains("Lightweight QueryIntent (qualitative generation scope only)",
                        "Only answer the topics explicitly requested by the user")
                .doesNotContain("Numeric answer contract");
    }

    @Test
    void derivesMandatoryAnswerLanguageFromTheUserQuestion() {
        assertThat(service.answerLanguage("Compare AEP and ED.")).isEqualTo("English");
        assertThat(service.answerLanguage("\u8bf7\u6bd4\u8f83 AEP \u548c ED\u3002")).isEqualTo("Chinese");
    }

    @Test
    void centersLongEvidenceOnTheMostSpecificQuestionTerm() {
        String content = "irrelevant ".repeat(250)
                + "quantitative information about material sales drivers and pricing"
                + " trailing".repeat(250);

        String window = service.centeredEvidenceWindow(
                content, "quantitative pricing disclosure", 600);

        assertThat(window).hasSizeLessThanOrEqualTo(610)
                .contains("quantitative information", "pricing")
                .doesNotStartWith("irrelevant irrelevant irrelevant irrelevant irrelevant");
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

        assertThat(missing).anyMatch(value -> value.contains("retrieve_msft"))
                .anyMatch(value -> value.startsWith("comparison_clause"));
    }

    @Test
    void specializesAnswerContractForVerifiedCalculations() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_count", "LIN executive candidate count", List.of("LIN"), List.of("2023", "2024"),
                "candidate count", "retrieve", "table", List.of());
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "How did the count change?", "How did the count change?", "calculation", List.of(), List.of(task));
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(), List.of(), List.of(new FinancialEvidenceLedger.VerifiedCalculation(
                "C1", "difference", "DIFFERENCE(F2 - F1)", new java.math.BigDecimal("5"),
                "count", "unit", List.of("F2", "F1"))));

        assertThat(service.answerContract(plan, ledger))
                .contains("chronological order", "one plain-language result sentence")
                .doesNotContain("Top-10", "topK");
    }

    @Test
    void givesHeaderOnlyTasksAValueSafetyContract() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_header", "NFLX table header period labels", List.of("NFLX"), List.of("2024"),
                "years covered", "retrieve", "table", List.of());
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "Which years are shown?", "Which years are shown?", "factual", List.of(), List.of(task));

        assertThat(service.answerContract(plan, new FinancialEvidenceLedger.Ledger(
                List.of(), List.of(), List.of())))
                .contains("labels or periods", "Do not claim or calculate a financial value");
    }

    @Test
    void distinguishesScopeOnlyPassagesFromDirectNumericSupport() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_income", "LLY net income FY2022 Item 7", List.of("LLY"), List.of("2022"),
                "net income", "retrieve", "hybrid", List.of());
        FinancialRagService.FinancialChunk scopeOnly = new FinancialRagService.FinancialChunk(
                "scope", "LLY_2022.html", "text",
                "Net income is discussed for fiscal 2022. Pension sensitivity was 50 basis points.",
                Map.of("company", "LLY", "year", "2022", "item", "Item 7"), 0.9)
                .withMatchedTask(task.id());
        FinancialRagService.FinancialChunk direct = new FinancialRagService.FinancialChunk(
                "direct", "LLY_2022.html", "text", "Net income was $6,244.8 million in fiscal 2022.",
                Map.of("company", "LLY", "year", "2022", "item", "Item 7"), 0.8)
                .withMatchedTask(task.id());

        assertThat(service.evidenceSupport(task, scopeOnly))
                .isEqualTo(FinancialRagService.EvidenceSupport.SCOPE_ONLY);
        assertThat(service.evidenceSupport(task, direct))
                .isEqualTo(FinancialRagService.EvidenceSupport.DIRECT_SUPPORT);
        assertThat(service.missingDirectSupport(List.of(task), List.of(scopeOnly)))
                .singleElement().asString().contains("missing_direct_support", "net income");
        assertThat(service.missingDirectSupport(List.of(task), List.of(direct))).isEmpty();
    }

    @Test
    void detectsMissingInterpretationAndComparisonChallengeClauses() {
        String question = "What does this increase imply about the trajectory, and what challenge does the "
                + "different disclosure approach present for cross-company comparison?";
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                question, question, "comparison", List.of(), List.of());
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(), List.of(), List.of());

        List<String> missing = service.missingAnswerCoverage(
                "NFLX increased. META reports estimated user metrics.", plan, ledger);

        assertThat(missing).anyMatch(value -> value.startsWith("comparison_clause"))
                .anyMatch(value -> value.startsWith("interpretation_clause"))
                .anyMatch(value -> value.startsWith("analysis_challenge_clause"));
        assertThat(service.missingAnswerCoverage(
                "The increase indicates re-acceleration. In contrast, META uses estimated people metrics; "
                        + "this asymmetry limits direct comparison and presents a cross-company challenge.",
                plan, ledger)).isEmpty();
    }

    @Test
    void flagsComparativeNumberWithoutVerifiedCalculationResult() {
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(), List.of(), List.of(
                new FinancialEvidenceLedger.VerifiedCalculation(
                        "C1", "percentage_change", "PERCENT_CHANGE(F1 -> F2)",
                        new java.math.BigDecimal("23.4"), "percent", "unit", List.of("F1", "F2")),
                new FinancialEvidenceLedger.VerifiedCalculation(
                        "C2", "percentage_change", "PERCENT_CHANGE(F3 -> F4)",
                        new java.math.BigDecimal("30.7"), "percent", "unit", List.of("F3", "F4"))));

        assertThat(service.unsupportedComparativeNumbers(
                "Membership growth outpaced revenue growth by 7.3 percentage points [C1][C2].",
                "How do the growth rates compare?", ledger))
                .containsExactly("7.3 percentage points");
        assertThat(service.unsupportedComparativeNumbers(
                "Revenue increased 23.4 percent [C1].",
                "How did revenue change?", ledger)).isEmpty();
    }

    @Test
    void treatsCitedFactWithMatchingCompanyAndYearAsCovered() {
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "question", "question", "calculation", List.of(), List.of(
                new FinancialRagService.FinancialRetrievalTask(
                        "retrieve_dis_2023", "DIS net income", List.of("DIS"), List.of("2023"),
                        "net income", "retrieve", "table", List.of())));
        FinancialEvidenceLedger.EvidenceDocument evidence = new FinancialEvidenceLedger.EvidenceDocument(
                "E1", "chunk", "DIS_2024.html", "DIS", "2024", "text", "Item 7", "MD&A",
                "Fiscal 2023 net income attributable to Disney was $2354 million.", List.of("retrieve_explicit_dis"));
        FinancialEvidenceLedger.FinancialFact fact = new FinancialEvidenceLedger.FinancialFact(
                "F1", "E1", "DIS", "2023", "net income attributable to Disney", "$2354 million",
                new java.math.BigDecimal("2354"), "usd", "million", "$2354 million");
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(evidence), List.of(fact), List.of());

        assertThat(service.missingAnswerCoverage("DIS FY2023 was $2.4 billion [F1][E1].", plan, ledger))
                .isEmpty();
    }

    @Test
    void rejectsRegionalTableForCompanyLevelMetricAndKeepsConsolidatedTable() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_nflx_total", "NFLX FY2024 total revenues", List.of("NFLX"), List.of("2024"),
                "Total revenues", "retrieve", "table", List.of());
        String regional = """
                Table context before:
                Europe, Middle East, and Africa (EMEA)

                Table:
                | Streaming revenues | $ | 12,387,035 |
                | Paid memberships at end of period | 101,133 |
                """;
        String consolidated = """
                Table context before:
                The following represents our consolidated performance highlights:

                Table:
                | Financial Results: | |
                | Total revenues | $ | 39,000,966 |
                | Global Streaming Memberships: | |
                """;
        FinancialRagService.FinancialChunk regionalChunk = new FinancialRagService.FinancialChunk(
                "regional", "NFLX_2024.html", "table", regional,
                Map.of("company", "NFLX", "year", "2024", "item", "Item 7"), 0.95)
                .withMatchedTask(task.id());
        FinancialRagService.FinancialChunk consolidatedChunk = new FinancialRagService.FinancialChunk(
                "consolidated", "NFLX_2024.html", "table", consolidated,
                Map.of("company", "NFLX", "year", "2024", "item", "Item 7"), 0.80)
                .withMatchedTask(task.id());

        List<FinancialRagService.FinancialChunk> selected = service.verifyAndSelectEvidence(
                new FinancialRagService.FinancialRetrievalPlan(
                        "question", "question", "calculation", List.of(), List.of(task)),
                List.of(regionalChunk, consolidatedChunk), 4, 2);

        assertThat(service.isRegionalBreakdownEvidence(regional)).isTrue();
        assertThat(service.isRegionalBreakdownEvidence(consolidated)).isFalse();
        assertThat(selected).hasSize(1);
        assertThat(service.evidenceMatchesTaskScope(task, selected.get(0))).isTrue();
    }

    @Test
    void rejectsRevenueOnlyTableForPaidMembershipTask() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_nflx_memberships", "NFLX FY2024 paid memberships", List.of("NFLX"), List.of("2024"),
                "paid memberships at end of period", "retrieve", "table", List.of());
        FinancialRagService.FinancialChunk revenueOnly = new FinancialRagService.FinancialChunk(
                "revenue-only", "NFLX_2024.html", "table", "| Streaming revenues | $ | 39,000,966 |",
                Map.of("company", "NFLX", "year", "2024", "item", "Item 7"), 0.95)
                .withMatchedTask(task.id());
        FinancialRagService.FinancialChunk membership = new FinancialRagService.FinancialChunk(
                "membership", "NFLX_2024.html", "table", """
                Table context before: consolidated performance highlights

                Table:
                | Global Streaming Memberships: | |
                | Paid memberships at end of period | 301,626 |
                """, Map.of("company", "NFLX", "year", "2024", "item", "Item 7"), 0.80)
                .withMatchedTask(task.id());

        assertThat(service.evidenceMatchesTaskScope(task, revenueOnly)).isFalse();
        assertThat(service.evidenceMatchesTaskScope(task, membership)).isTrue();
    }

    @Test
    void reusesOneScopedAggregateTableAcrossItsDirectNumericTasks() {
        FinancialRagService.FinancialRetrievalTask revenueTask = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_revenue", "NFLX FY2024 total revenues", List.of("NFLX"), List.of("2024"),
                "total revenues", "retrieve", "table", List.of());
        FinancialRagService.FinancialRetrievalTask membershipTask = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_memberships", "NFLX FY2024 paid memberships", List.of("NFLX"), List.of("2024"),
                "paid memberships at end of period", "retrieve", "table", List.of());
        FinancialRagService.FinancialChunk aggregate = new FinancialRagService.FinancialChunk(
                "aggregate", "NFLX_2024.html", "table", """
                | Total revenues | $ | 39,000,966 |
                | Paid memberships at end of period (2) | 301,626 |
                """, Map.of("company", "NFLX", "year", "2024", "item", "Item 7"), 0.9)
                .withMatchedTask(revenueTask.id());
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "question", "question", "calculation", List.of(), List.of(revenueTask, membershipTask));

        List<FinancialRagService.FinancialChunk> selected = service.verifyAndSelectEvidence(
                plan, List.of(aggregate), 4, 2);

        assertThat(selected).singleElement().satisfies(chunk ->
                assertThat(chunk.matchedTaskIds()).containsExactlyInAnyOrder(
                        revenueTask.id(), membershipTask.id()));
    }

    @Test
    void repairsMismatchedCalculationTaskBindingAndCompletesStructuredYearCount() {
        FinancialRagService.FinancialRetrievalTask wrongYear = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_dis_2023", "DIS 2023 net income", List.of("DIS"), List.of("2023"),
                "net income", "retrieve", "table", List.of());
        FinancialRagService.FinancialRetrievalTask correctYear = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_dis_2024", "DIS 2024 net income increase", List.of("DIS"), List.of("2024"),
                "net income increase", "retrieve", "text", List.of());
        FinancialRagService.FinancialRetrievalTask header = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_nflx_header", "NFLX table header fiscal years 2022 2021 2020", List.of("NFLX"),
                List.of("2022"), "fiscal years covered in table header", "retrieve", "table", List.of());
        FinancialEvidenceLedger.CalculationPlan subtract = new FinancialEvidenceLedger.CalculationPlan(
                "prior", FinancialEvidenceLedger.CalculationOperator.DIFFERENCE, List.of(
                new FinancialEvidenceLedger.CalculationOperand(
                        "start", wrongYear.id(), "DIS", "2024", "net income"),
                new FinancialEvidenceLedger.CalculationOperand(
                        "component", wrongYear.id(), "DIS", "2024", "net income increase")),
                "usd", "billion", 1, 0);

        List<FinancialEvidenceLedger.CalculationPlan> repaired = service.repairCalculationPlanBindings(
                List.of(wrongYear, correctYear, header), List.of(subtract));
        List<FinancialEvidenceLedger.CalculationPlan> completed = service.completeStructuredCountPlans(
                List.of(wrongYear, correctYear, header), repaired);

        assertThat(repaired.get(0).operands())
                .extracting(FinancialEvidenceLedger.CalculationOperand::sourceTaskId)
                .containsOnly(correctYear.id());
        assertThat(completed).hasSize(2);
        assertThat(completed.get(1).operator()).isEqualTo(FinancialEvidenceLedger.CalculationOperator.COUNT);
        assertThat(completed.get(1).operands())
                .extracting(FinancialEvidenceLedger.CalculationOperand::fiscalYear)
                .containsExactly("2022", "2021", "2020");
    }

    @Test
    void removesValueCalculationBoundOnlyToHeaderEvidenceAndCropsRowsFromHeaderContext() {
        FinancialRagService.FinancialRetrievalTask header = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_header", "NFLX table headers and period labels", List.of("NFLX"), List.of("2024"),
                "revenue table headers", "retrieve", "table", List.of());
        FinancialEvidenceLedger.CalculationPlan invalidGrowth = new FinancialEvidenceLedger.CalculationPlan(
                "growth", FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE, List.of(
                new FinancialEvidenceLedger.CalculationOperand(
                        "start", header.id(), "NFLX", "2023", "total revenues"),
                new FinancialEvidenceLedger.CalculationOperand(
                        "end", header.id(), "NFLX", "2024", "total revenues")),
                "percent", "unit", 1, 1);
        String table = """
                Table context before:
                consolidated performance

                Table:
                Table header: As of/Year Ended December 31, | 2024 | 2023 | 2022 |
                |  | 2024 | 2023 | 2022 |
                |  | (in thousands) | | |
                | Total revenues | 39,000,966 | 33,723,297 | 31,615,550 |
                """;

        assertThat(service.rejectHeaderOnlyValuePlans(List.of(header), List.of(invalidGrowth))).isEmpty();
        assertThat(service.headerOnlyEvidenceContent(table, header.query(), 1000))
                .contains("Table header:", "2024", "2023", "2022")
                .doesNotContain("39,000,966", "Total revenues");
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
    void extractionRetryPrioritizesDocumentsForMissingNumericalTasks() {
        List<FinancialEvidenceLedger.EvidenceDocument> documents = List.of(
                evidence("E1", "task_complete"), evidence("E2", "task_complete"),
                evidence("E3", "task_missing"));

        List<FinancialEvidenceLedger.EvidenceDocument> compact =
                service.compactExtractionDocuments(documents, 2, Set.of("task_missing"));

        assertThat(compact).extracting(FinancialEvidenceLedger.EvidenceDocument::evidenceId)
                .containsExactly("E3", "E1");
    }

    @Test
    void detectsMissingFactForNumericRetrievalTaskWithoutCalculationPlan() {
        FinancialRagService.FinancialRetrievalTask task = new FinancialRagService.FinancialRetrievalTask(
                "retrieve_nflx_margin", "NFLX FY2024 operating margin", List.of("NFLX"),
                List.of("2024"), "operating margin", "retrieve", "hybrid", List.of());
        FinancialRagService.FinancialRetrievalPlan plan = new FinancialRagService.FinancialRetrievalPlan(
                "Compare operating margin", "Compare operating margin", "comparison",
                List.of(), List.of(task));
        FinancialEvidenceLedger.EvidenceDocument document = new FinancialEvidenceLedger.EvidenceDocument(
                "E1", "chunk-E1", "NFLX_2024.html", "NFLX", "2024", "table", "Item 7", "MD&A",
                "| Operating margin | 27% |", List.of(task.id()));
        FinancialEvidenceLedger.Ledger ledger = new FinancialEvidenceLedger.Ledger(
                List.of(document), List.of(), List.of());

        assertThat(service.missingNumericalTaskIds(plan, ledger)).containsExactly(task.id());
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

    private FinancialEvidenceLedger.EvidenceDocument descriptionEvidence(
            String id, String company, String year, String content) {
        return new FinancialEvidenceLedger.EvidenceDocument(
                id, "chunk-" + id, company + "_" + year + ".html", company, year,
                "text", "Item 7", "MD&A", content, List.of("retrieve_" + company.toLowerCase()));
    }
}
