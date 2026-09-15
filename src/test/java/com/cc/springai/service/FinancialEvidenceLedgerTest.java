package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialEvidenceLedgerTest {

    private final FinancialEvidenceLedger ledger = new FinancialEvidenceLedger(new ObjectMapper());

    @Test
    void extractionPromptDoesNotTreatHeaderTokensAsFinancialValues() {
        String prompt = ledger.extractionPrompt("What was revenue?", List.of(document(
                "E1", "NFLX_2024.html", "NFLX", "2024",
                "Table header: 2024 | 2023 | 2022 | (in thousands)")));

        assertThat(prompt).contains(
                "A table header contains labels, periods, units and column structure, not row values",
                "a year, date, unit, or other header token as the requested financial value");
    }

    @Test
    void sourceTableHeaderOverridesInventedRawMagnitude() {
        var evidence = List.of(document("E1", "AAA_2024.html", "AAA", "2024",
                "Table header: 2024 | (in thousands)\n| Total revenues | 1,000 |"));
        var result = ledger.build("total revenues", evidence, """
                {"facts":[{"evidenceId":"E1","metric":"total revenues","rawValue":"1,000 million",
                 "unit":"USD","scale":"million","quote":"| Total revenues | 1,000 |"}]}
                """);
        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).scale()).isEqualTo("thousand");
        assertThat(result.render()).contains("1000 thousand usd").doesNotContain("1,000 million");
    }

    @Test
    void usesDisplayedScaleForMixedMagnitudeCalculation() {
        var evidence = List.of(
                document("E1", "AAA_2023.html", "AAA", "2023", "Revenue was $1,000 thousand."),
                document("E2", "AAA_2024.html", "AAA", "2024", "Revenue was $2 million."));
        var result = ledger.build("percentage revenue growth", evidence, """
                {"facts":[
                {"evidenceId":"E1","metric":"revenue","rawValue":"$1,000 thousand","unit":"USD",
                 "scale":"million","quote":"Revenue was $1,000 thousand."},
                {"evidenceId":"E2","metric":"revenue","rawValue":"$2 million","unit":"USD",
                 "scale":"thousand","quote":"Revenue was $2 million."}]}
                """, List.of(plan(FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                operand("start", "AAA", "2023", "revenue"), operand("end", "AAA", "2024", "revenue"))));
        assertThat(result.facts().get(0).scale()).isEqualTo("thousand");
        assertThat(result.facts().get(1).scale()).isEqualTo("million");
        assertThat(result.calculations()).hasSize(1);
        assertThat(result.calculations().get(0).displayResult()).isEqualTo("100");
    }

    @Test
    void preservesDecimalSeparatedByHtmlWhitespace() {
        var evidence = List.of(document("E1", "META_2024.html", "META", "2024",
                "Annual worldwide ARPP was $ 49. 63."));
        var result = ledger.build("What was ARPP?", evidence, """
                {"facts":[{"evidenceId":"E1","metric":"ARPP","rawValue":"$ 49. 63",
                "unit":"USD","scale":"unit","quote":"Annual worldwide ARPP was $ 49. 63."}]}
                """);
        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).value()).isEqualByComparingTo("49.63");
    }

    @Test
    void rejectsFactAssignedToAnotherCompany() {
        var evidence = List.of(document("E1", "AAA_2024.html", "AAA", "2024", "Revenue was $100 million."));
        var result = ledger.build("revenue", evidence, """
                {"facts":[{"evidenceId":"E1","company":"BBB","metric":"revenue","rawValue":"100",
                "quote":"Revenue was $100 million.","unit":"USD","scale":"million"}]}
                """);
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void verifiesQuotesAndCalculatesPercentageChangeDeterministically() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "AAPL_2023.html", "AAPL", "2023", "Net sales were $100 million in fiscal 2023."),
                document("E2", "AAPL_2024.html", "AAPL", "2024", "Net sales were $120 million in fiscal 2024."));

        FinancialEvidenceLedger.Ledger result = ledger.build(
                "What was the percentage change in net sales from 2023 to 2024?",
                evidence,
                """
                        {"facts":[
                          {"evidenceId":"E1","company":"AAPL","fiscalYear":"2023","metric":"net sales","rawValue":"$100 million","value":"100","unit":"USD","scale":"million","quote":"Net sales were $100 million in fiscal 2023."},
                          {"evidenceId":"E2","company":"AAPL","fiscalYear":"2024","metric":"net sales","rawValue":"$120 million","value":"120","unit":"USD","scale":"million","quote":"Net sales were $120 million in fiscal 2024."}
                        ]}
                        """, List.of(plan(FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                        operand("start", "AAPL", "2023", "net sales"),
                        operand("end", "AAPL", "2024", "net sales"))));

        assertThat(result.facts()).hasSize(2);
        assertThat(result.calculations()).hasSize(1);
        assertThat(result.calculations().get(0).type()).isEqualTo("percentage_change");
        assertThat(result.calculations().get(0).displayResult()).isEqualTo("20");
        assertThat(result.render()).contains("[C1]", "[E1]", "[E2]");
    }

    @Test
    void rejectsAFactWhenItsQuoteIsNotInTheEvidence() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "AAPL_2024.html", "AAPL", "2024", "Net sales were $120 million."));

        FinancialEvidenceLedger.Ledger result = ledger.build(
                "What were net sales?",
                evidence,
                """
                        {"facts":[
                          {"evidenceId":"E1","metric":"net sales","rawValue":"$999 million","value":"999","unit":"USD","scale":"million","quote":"Net sales were $999 million."}
                        ]}
                        """);

        assertThat(result.facts()).isEmpty();
        assertThat(result.calculations()).isEmpty();
    }

    @Test
    void acceptsDisplayedUnitWhenExactTableQuoteContainsTheSameNumber() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "LLY_2022.html", "LLY", "2022", "| Revenue | 28,541.4 |"));

        FinancialEvidenceLedger.Ledger result = ledger.build("What was revenue?", evidence, """
                {"facts":[{"evidenceId":"E1","company":"LLY","fiscalYear":"2022",
                "metric":"revenue","rawValue":"$28,541.4 million","value":"28541.4",
                "unit":"USD","scale":"million","quote":"| Revenue | 28,541.4 |"}]}
                """);

        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).value()).isEqualByComparingTo("28541.4");
    }

    @Test
    void augmentsPartialExtractionWithoutDiscardingVerifiedFacts() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "LLY_2022.html", "LLY", "2022", "Revenue was 100 million."),
                document("E2", "LLY_2024.html", "LLY", "2024", "Revenue was 150 million."));
        FinancialEvidenceLedger.CalculationPlan percentageChange = plan(
                FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                operand("start", "LLY", "2022", "revenue"),
                operand("end", "LLY", "2024", "revenue"));
        FinancialEvidenceLedger.Ledger partial = ledger.build("percentage change from 2022 to 2024", evidence, """
                {"facts":[{"evidenceId":"E1","metric":"revenue","rawValue":"100 million",
                "value":"100","unit":"USD","scale":"million","quote":"Revenue was 100 million."}]}
                """, List.of(percentageChange));

        FinancialEvidenceLedger.Ledger augmented = ledger.augment("percentage change from 2022 to 2024", partial, """
                {"facts":[{"evidenceId":"E2","metric":"revenue","rawValue":"150 million",
                "value":"150","unit":"USD","scale":"million","quote":"Revenue was 150 million."}]}
                """, List.of(percentageChange));

        assertThat(augmented.facts()).hasSize(2);
        assertThat(augmented.calculations()).hasSize(1);
        assertThat(augmented.calculations().get(0).displayResult()).isEqualTo("50");
    }

    @Test
    void derivesNormalizedValueFromRawValueAndDeduplicatesModelScaleErrors() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "DIS_2024.html", "DIS", "2024", "Net income attributable to Disney $ 4,972 $ 2,354"));

        FinancialEvidenceLedger.Ledger result = ledger.build("What was net income?", evidence, """
                {"facts":[
                  {"evidenceId":"E1","fiscalYear":"2024","metric":"Net income attributable to Disney",
                   "rawValue":"$4,972","value":"4972","unit":"USD","scale":"million",
                   "quote":"Net income attributable to Disney $ 4,972 $ 2,354"},
                  {"evidenceId":"E1","fiscalYear":"2024","metric":"net income",
                   "rawValue":"$4,972","value":"4.972","unit":"USD","scale":"million",
                   "quote":"Net income attributable to Disney $ 4,972 $ 2,354"}
                ]}
                """);

        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).value()).isEqualByComparingTo("4972");
    }

    @Test
    void countsFiscalYearsExplicitlyListedInAQuestionWhenEvidenceConfirmsThem() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "NFLX_2022.html", "NFLX", "2022",
                        "for the years ended December 31, 2022, 2021 and 2020"));

        FinancialEvidenceLedger.Ledger result = ledger.build(
                "The table includes years 2022, 2021, and 2020. How many fiscal years are covered?",
                evidence, "{\"facts\":[]}", List.of(plan(FinancialEvidenceLedger.CalculationOperator.COUNT,
                        operand("year", "NFLX", "2022", ""),
                        operand("year", "NFLX", "2021", ""),
                        operand("year", "NFLX", "2020", ""))));

        assertThat(result.calculations()).hasSize(1);
        assertThat(result.calculations().get(0).type()).isEqualTo("count");
        assertThat(result.calculations().get(0).displayResult()).isEqualTo("3");
    }

    @Test
    void deterministicallyExtractsCashComponentsFromRetrievedTableRows() {
        String balanceSheet = """
                Table header: December 28, 2024 | (In millions)
                | Cash and cash equivalents | $ | 3,787 |
                | Short-term investments | 1,345 |
                """;
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "AMD_2024.html", "AMD", "2024", balanceSheet),
                document("E2", "AMD_2024.html", "AMD", "2024", balanceSheet));

        FinancialEvidenceLedger.Ledger result = ledger.build(
                "Compare total cash, cash equivalents, and short-term investments", evidence, "{\"facts\":[]}",
                List.of(plan(FinancialEvidenceLedger.CalculationOperator.SUM,
                        operand("component", "AMD", "2024", "cash and cash equivalents"),
                        operand("component", "AMD", "2024", "short-term investments"))));

        assertThat(result.facts()).hasSize(2);
        assertThat(result.calculations()).hasSize(1);
        assertThat(result.calculations().get(0).displayResult()).isEqualTo("5132");
    }

    @Test
    void deterministicallyExtractsCombinedCashTotalFromNarrativeText() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "MSFT_2024.html", "MSFT", "2024",
                        "Cash, cash equivalents, and short-term investments totaled $75.5 billion as of June 30, 2024."));

        FinancialEvidenceLedger.Ledger result = ledger.build(
                "Compare total cash, cash equivalents, and short-term investments", evidence, "{\"facts\":[]}");

        assertThat(result.facts()).hasSize(1);
        assertThat(result.facts().get(0).company()).isEqualTo("MSFT");
        assertThat(result.facts().get(0).value()).isEqualByComparingTo("75.5");
        assertThat(result.facts().get(0).scale()).isEqualTo("billion");
    }

    @Test
    void usesStatedRoundedCurrentValueToDisambiguateNetIncomeRows() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "DIS_2023.html", "DIS", "2023", """
                        Table header: 2023 | 2022 | ($ in millions)
                        | Net income | 3,390 | 3,505 |
                        | Net income attributable to Disney | $ | 2,354 | $ | 3,145 |
                        """),
                document("E2", "DIS_2024.html", "DIS", "2024", """
                        Table header: 2024 | 2023 | ($ in millions)
                        | Net income | 5,773 | 3,390 |
                        | Net income attributable to Disney | $ | 4,972 | $ | 2,354 |
                        """));

        FinancialEvidenceLedger.Ledger result = ledger.build(
                "Net income for fiscal 2024 is $5.0 billion, an increase from the prior year. What was fiscal 2023?",
                evidence, "{\"facts\":[]}", List.of(plan(FinancialEvidenceLedger.CalculationOperator.DIFFERENCE,
                        operand("current", "DIS", "2024", "net income attributable to Disney"),
                        operand("prior", "DIS", "2023", "net income attributable to Disney"))));

        assertThat(result.facts()).extracting(FinancialEvidenceLedger.FinancialFact::value)
                .containsExactly(new java.math.BigDecimal("4972"), new java.math.BigDecimal("2354"));
        assertThat(result.calculations()).hasSize(1);
        assertThat(result.calculations().get(0).displayResult()).isEqualTo("2618");
    }

    @Test
    void rejectsAReportedMetricThatConflictsWithTheRoundedQuestionPremise() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "DIS_2024.html", "DIS", "2024", """
                        Table header: 2024 | 2023 | ($ in millions)
                        | Net income | 5,773 | 3,390 |
                        """));

        FinancialEvidenceLedger.Ledger result = ledger.build(
                "Net income for fiscal 2024 is $5.0 billion, an increase from the prior year.",
                evidence, "{\"facts\":[]}");

        assertThat(result.facts()).isEmpty();
        assertThat(result.calculations()).isEmpty();
    }

    @Test
    void rejectsTableValueRelabeledAsAnotherMetric() {
        String content = """
                Table header: Years ended | September 28, 2024 | Net sales:
                | Net sales: |  |  |
                | Income before provision for income taxes | 123,485 | 113,736 |
                """;
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "AAPL_2024.html", "AAPL", "2024", content));

        FinancialEvidenceLedger.Ledger result = ledger.build("What were total net sales?", evidence, """
                {"facts":[{"evidenceId":"E1","company":"AAPL","fiscalYear":"2024",
                "metric":"total net sales","rawValue":"$123,485 million","value":"123485",
                "unit":"USD","scale":"million",
                "quote":"| Income before provision for income taxes | 123,485 | 113,736 |"}]}
                """);

        assertThat(result.facts()).isEmpty();
        assertThat(ledger.containsDirectMetricValue("total net sales", content)).isFalse();
    }

    @Test
    void acceptsDirectTotalMetricRow() {
        String content = "| Total net sales | $ | 391,035 | $ | 383,285 |";

        assertThat(ledger.containsDirectMetricValue("total net sales", content)).isTrue();
    }

    @Test
    void ignoresNumericFootnoteSuffixWhenMatchingMetricRow() {
        String content = "| Paid memberships at end of period (2) | 301,626 | 260,276 |";

        assertThat(ledger.containsDirectMetricValue(
                "Paid memberships at end of period", content)).isTrue();
    }

    @Test
    void acceptsUnderscoredTotalRevenueInNarrativeEvidence() {
        String content = "Revenues for fiscal 2024 increased 3%, or $2.5 billion, to $91.4 billion.";

        assertThat(ledger.containsDirectMetricValue("total_revenue", content)).isTrue();
    }

    @Test
    void doesNotTreatRevenueAccrualValuesAsDirectTotalRevenueEvidence() {
        String content = "Revenue recognition requires rebate and discount accruals. "
                + "The rebate accrual increased by $125 million in fiscal 2024.";

        assertThat(ledger.containsDirectMetricValue("total revenue", content)).isFalse();
    }

    @Test
    void calculationPlanDropsIncidentalFactsOutsideItsOperands() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "NFLX_2024.html", "NFLX", "2024",
                        "| Total revenues | 39,000,966 |"),
                document("E2", "META_2024.html", "META", "2024",
                        "| Revenue | 164,501 |"));
        FinancialEvidenceLedger.CalculationPlan growth = plan(
                FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                operand("end", "NFLX", "2024", "total revenues"));

        FinancialEvidenceLedger.Ledger result = ledger.build("Calculate NFLX revenue growth", evidence, """
                {"facts":[
                  {"evidenceId":"E1","company":"NFLX","fiscalYear":"2024","metric":"total revenues",
                   "rawValue":"39,000,966","value":"39000966","unit":"USD","scale":"thousand",
                   "quote":"| Total revenues | 39,000,966 |"},
                  {"evidenceId":"E2","company":"META","fiscalYear":"2024","metric":"revenue",
                   "rawValue":"164,501","value":"164501","unit":"USD","scale":"million",
                   "quote":"| Revenue | 164,501 |"}
                ]}
                """, List.of(growth));

        assertThat(result.facts()).singleElement()
                .extracting(FinancialEvidenceLedger.FinancialFact::company)
                .isEqualTo("NFLX");
    }

    @Test
    void requiresNarrativeMetricAndSubstantiveValueInTheSameLocalStatement() {
        String scopeOnly = "Net income is discussed for fiscal 2024. "
                + "The pension sensitivity was 50 basis points.";
        String direct = "Net income was $6,244.8 million in fiscal 2022.";

        assertThat(ledger.containsDirectMetricValue("net income", scopeOnly)).isFalse();
        assertThat(ledger.containsDirectMetricValue("net income", direct)).isTrue();
    }

    @Test
    void rejectsSensitivityImpactAsReportedNetIncomeValue() {
        String taxSensitivity = "As of December 31, 2022, a 5 percent change in uncertain tax positions "
                + "would result in a change in net income of $85.0 million.";
        String pensionSensitivity = "If the discount rate changed by a quarter percentage point, "
                + "net income would be affected by $35.9 million.";

        assertThat(ledger.containsDirectMetricValue("net income", taxSensitivity)).isFalse();
        assertThat(ledger.containsDirectMetricValue("net income", pensionSensitivity)).isFalse();
        assertThat(ledger.containsDirectMetricValue(
                "net income", "Net income was $10,590.0 million in fiscal 2024.")).isTrue();
    }

    @Test
    void recognizesEquivalentDilutedEpsLabels() {
        assertThat(ledger.containsDirectMetricValue(
                "diluted earnings per share", "Diluted EPS was $11.71 in fiscal 2024.")).isTrue();
        assertThat(ledger.containsDirectMetricValue(
                "diluted earnings per share", "| Earnings per share - diluted | $ | 11.71 |")).isTrue();
    }

    @Test
    void acceptsSecOfficerAgeQuotesAndExecutesTypedDifference() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                document("E1", "LIN_2022.html", "LIN", "2022", "Sanjiv Lamba, 58, was appointed CEO."),
                document("E2", "LIN_2023.html", "LIN", "2023", "Sanjiv Lamba, 59, was appointed CEO."));
        FinancialEvidenceLedger.CalculationPlan ageChange = plan(
                FinancialEvidenceLedger.CalculationOperator.DIFFERENCE,
                operand("end", "LIN", "2023", "executive_age"),
                operand("start", "LIN", "2022", "executive_age"));

        FinancialEvidenceLedger.Ledger result = ledger.build("How did Sanjiv Lamba's age change?", evidence, """
                {"facts":[
                  {"evidenceId":"E1","company":"LIN","fiscalYear":"2022","metric":"executive_age",
                   "rawValue":"58","value":"58","unit":"count","scale":"unit",
                   "quote":"Sanjiv Lamba, 58, was appointed CEO."},
                  {"evidenceId":"E2","company":"LIN","fiscalYear":"2023","metric":"executive_age",
                   "rawValue":"59","value":"59","unit":"count","scale":"unit",
                   "quote":"Sanjiv Lamba, 59, was appointed CEO."}
                ]}
                """, List.of(ageChange));

        assertThat(result.facts()).hasSize(2);
        assertThat(result.calculations()).hasSize(1);
        assertThat(result.calculations().get(0).displayResult()).isEqualTo("1");
    }

    @Test
    void convertsCompatibleValuesAcrossDifferentScales() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact("F1", "E1", "AAPL", "2023", "net sales", "100",
                        new java.math.BigDecimal("100"), "usd", "million", "100"),
                new FinancialEvidenceLedger.FinancialFact("F2", "E2", "AAPL", "2024", "net sales", "1.2",
                        new java.math.BigDecimal("1.2"), "usd", "billion", "1.2"));

        FinancialEvidenceLedger.CalculationPlan plan = new FinancialEvidenceLedger.CalculationPlan(
                "difference", FinancialEvidenceLedger.CalculationOperator.DIFFERENCE, List.of(
                operand("later", "AAPL", "2024", "net sales"),
                operand("earlier", "AAPL", "2023", "net sales")), "usd", "billion", 4, 0);
        List<FinancialEvidenceLedger.VerifiedCalculation> calculations =
                ledger.calculate(List.of(plan), facts, List.of());

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).displayResult()).isEqualTo("1.1");
        assertThat(calculations.get(0).scale()).isEqualTo("billion");
    }

    @Test
    void usesPlannerScaleOnlyToResolveRoundedAndPreciseAggregateDuplicates() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact("F1", "E1", "DIS", "2024", "total revenue",
                        "$91.4 billion", new BigDecimal("91.4"), "usd", "billion", "total revenue was $91.4 billion"),
                new FinancialEvidenceLedger.FinancialFact("F2", "E2", "DIS", "2024", "total revenue",
                        "91,361", new BigDecimal("91361"), "usd", "million", "| Total revenue | 91,361 |"),
                new FinancialEvidenceLedger.FinancialFact("F3", "E2", "DIS", "2023", "total revenue",
                        "88,898", new BigDecimal("88898"), "usd", "million", "| Total revenue | 88,898 |"));
        FinancialEvidenceLedger.CalculationPlan plan = new FinancialEvidenceLedger.CalculationPlan(
                "growth", FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE, List.of(
                new FinancialEvidenceLedger.CalculationOperand(
                        "start", "", "DIS", "2023", "total revenue", "", "usd", "billion"),
                new FinancialEvidenceLedger.CalculationOperand(
                        "end", "", "DIS", "2024", "total revenue", "", "usd", "billion")),
                "percent", "unit", 1, 0);

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations =
                ledger.calculate(List.of(plan), facts, List.of());

        assertThat(calculations).singleElement().satisfies(calculation -> {
            assertThat(calculation.displayResult()).isEqualTo("2.8");
            assertThat(calculation.sourceFactIds()).containsExactly("F3", "F1");
        });
    }

    @Test
    void doesNotCalculateDifferenceForQualitativeComparison() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                fact("F1", "100", "million"),
                fact("F2", "120", "million"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations =
                ledger.calculate(List.of(), facts, List.of());

        assertThat(calculations).isEmpty();
    }

    @Test
    void doesNotCalculateDifferenceForWordingChangeQuestion() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                fact("F1", "100", "million"),
                fact("F2", "120", "million"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations =
                ledger.calculate(List.of(), facts, List.of());

        assertThat(calculations).isEmpty();
    }

    @Test
    void calculatesMarginAcrossDifferentMetricsWithCompatibleUnits() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact(
                        "F1", "E1", "LLY", "2020", "income taxes", "14.33",
                        new java.math.BigDecimal("14.33"), "usd", "million", "14.33"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F2", "E1", "LLY", "2020", "income before income taxes", "100",
                        new java.math.BigDecimal("100"), "usd", "million", "100"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations = ledger.calculate(
                List.of(plan(FinancialEvidenceLedger.CalculationOperator.MARGIN,
                        operand("numerator", "LLY", "2020", "income taxes"),
                        operand("denominator", "LLY", "2020", "income before income taxes"))), facts, List.of());

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).type()).isEqualTo("margin");
        assertThat(calculations.get(0).displayResult()).isEqualTo("14.33");
    }

    @Test
    void calculatesHowManyTimesLargerForTheNamedCompany() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact(
                        "F1", "E1", "NVDA", "2024", "total revenue", "$60,922 million",
                        new java.math.BigDecimal("60922"), "usd", "million", "$60,922 million"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F2", "E2", "AAPL", "2024", "total net sales", "$391,035 million",
                        new java.math.BigDecimal("391035"), "usd", "million", "$391,035 million"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations = ledger.calculate(
                List.of(plan(FinancialEvidenceLedger.CalculationOperator.RATIO,
                        operand("numerator", "AAPL", "2024", "total net sales"),
                        operand("denominator", "NVDA", "2024", "total revenue"))), facts, List.of());

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).type()).isEqualTo("multiple");
        assertThat(calculations.get(0).displayResult()).isEqualTo("6.4186");
        assertThat(calculations.get(0).unit()).isEqualTo("times");
        assertThat(calculations.get(0).sourceFactIds()).containsExactly("F2", "F1");
    }

    @Test
    void treatsRevenueAndNetSalesAsTheSameMetricForTrends() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact(
                        "F1", "E1", "AAPL", "2023", "revenue", "$100 million",
                        new java.math.BigDecimal("100"), "usd", "million", "$100 million"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F2", "E2", "AAPL", "2024", "net sales", "$110 million",
                        new java.math.BigDecimal("110"), "usd", "million", "$110 million"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations = ledger.calculate(
                List.of(plan(FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                        operand("start", "AAPL", "2023", "revenue"),
                        operand("end", "AAPL", "2024", "net sales"))), facts, List.of());

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).displayResult()).isEqualTo("10");
    }

    @Test
    void calculatesOnlyTheYearPairRequestedWhenAnotherYearIsAlsoMentioned() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                ageFact("F1", "2022", "48"),
                ageFact("F2", "2023", "49"),
                ageFact("F3", "2024", "50"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations = ledger.calculate(
                List.of(plan(FinancialEvidenceLedger.CalculationOperator.DIFFERENCE,
                        operand("later", "LIN", "2023", "age"),
                        operand("earlier", "LIN", "2022", "age"))), facts, List.of());

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).displayResult()).isEqualTo("1");
        assertThat(calculations.get(0).sourceFactIds()).containsExactly("F2", "F1");
    }

    @Test
    void prefersSameCompanyOperandsInMixedCompanyQuestion() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact(
                        "F1", "E1", "DIS", "2024", "Net income attributable to Disney", "$4972 million",
                        new java.math.BigDecimal("4972"), "usd", "million", "$4972 million"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F2", "E1", "DIS", "2023", "Net income attributable to Disney", "$2354 million",
                        new java.math.BigDecimal("2354"), "usd", "million", "$2354 million"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F3", "E2", "NFLX", "2022", "Net income", "$4491924 thousand",
                        new java.math.BigDecimal("4491924"), "usd", "thousand", "$4491924 thousand"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations = ledger.calculate(
                List.of(plan(FinancialEvidenceLedger.CalculationOperator.DIFFERENCE,
                        operand("current", "DIS", "2024", "net income attributable to Disney"),
                        operand("prior", "DIS", "2023", "net income attributable to Disney"))), facts, List.of());

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).displayResult()).isEqualTo("2618");
        assertThat(calculations.get(0).sourceFactIds()).containsExactly("F1", "F2");
    }

    @Test
    void calculatesTotalsPerCompanyWithoutDoubleCountingADirectTotal() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                cashFact("F1", "AMD", "cash and cash equivalents", "3787"),
                cashFact("F2", "AMD", "short-term investments", "1345"),
                cashFact("F3", "MSFT", "total cash, cash equivalents and short-term investments", "75500"),
                cashFact("F4", "MSFT", "cash and cash equivalents", "18315"),
                cashFact("F5", "MSFT", "short-term investments", "57228"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations = ledger.calculate(
                List.of(plan(FinancialEvidenceLedger.CalculationOperator.SUM,
                        operand("component", "AMD", "2024", "cash and cash equivalents"),
                        operand("component", "AMD", "2024", "short-term investments"))), facts, List.of());

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).displayResult()).isEqualTo("5132");
        assertThat(calculations.get(0).sourceFactIds()).containsExactly("F1", "F2");
    }

    @Test
    void doesNotTreatStreamingRevenueAsCompanyTotalRevenue() {
        assertThat(ledger.containsDirectMetricValue(
                "Total revenues", "| Streaming revenues | $ | 17,359,369 |"))
                .isFalse();
        assertThat(ledger.containsDirectMetricValue(
                "Total revenues", "| Revenues | $ | 39,000,966 |"))
                .isTrue();
        assertThat(ledger.containsDirectMetricValue(
                "Total revenues", "| Total revenues | $ | 39,000,966 |"))
                .isTrue();
    }

    @Test
    void groundsPlannerInputsInEvidenceBeforeExecutingPriorValueCalculation() {
        FinancialEvidenceLedger.EvidenceDocument evidence = new FinancialEvidenceLedger.EvidenceDocument(
                "E1", "chunk-E1", "DIS_2024.html", "DIS", "2024", "text", "Item 7", "MD&A",
                "Net income for fiscal 2024 was $5.0 billion, an increase of $2.6 billion from the prior year.",
                List.of("retrieve_explicit_dis_2024_anchor"));
        FinancialEvidenceLedger.CalculationPlan priorIncome = new FinancialEvidenceLedger.CalculationPlan(
                "prior_income", FinancialEvidenceLedger.CalculationOperator.DIFFERENCE, List.of(
                new FinancialEvidenceLedger.CalculationOperand(
                        "start", "retrieve_dis_net_income", "DIS", "2024", "net income",
                        "$5.0 billion", "usd", "billion"),
                new FinancialEvidenceLedger.CalculationOperand(
                        "component", "retrieve_dis_net_income_increase", "DIS", "2024", "net income increase",
                        "$2.6 billion", "usd", "billion")),
                "usd", "billion", 1, 0);

        FinancialEvidenceLedger.Ledger result = ledger.build(
                "What was prior-year net income?", List.of(evidence), "{\"facts\":[]}", List.of(priorIncome));

        assertThat(result.facts()).extracting(FinancialEvidenceLedger.FinancialFact::value)
                .containsExactly(new BigDecimal("5.0"), new BigDecimal("2.6"));
        assertThat(result.calculations()).singleElement()
                .extracting(FinancialEvidenceLedger.VerifiedCalculation::result)
                .isEqualTo(new BigDecimal("2.4"));
    }

    @Test
    void bindsHistoricalTableColumnDespiteFilingYearTaskTag() {
        FinancialEvidenceLedger.EvidenceDocument evidence = new FinancialEvidenceLedger.EvidenceDocument(
                "E1", "chunk-E1", "NFLX_2024.html", "NFLX", "2024", "table", "Item 7", "MD&A",
                "| Paid net membership additions | 41,350 | 29,529 | 8,903 |",
                List.of("retrieve_nflx_2024"));
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact(
                        "F1", "E1", "NFLX", "2022", "paid net membership additions", "8,903",
                        new BigDecimal("8903"), "count", "thousand", "8,903"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F2", "E1", "NFLX", "2024", "paid net membership additions", "41,350",
                        new BigDecimal("41350"), "count", "thousand", "41,350"));
        FinancialEvidenceLedger.CalculationPlan growth = new FinancialEvidenceLedger.CalculationPlan(
                "growth", FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE, List.of(
                new FinancialEvidenceLedger.CalculationOperand(
                        "start", "retrieve_nflx_2022", "NFLX", "2022", "paid net membership additions"),
                new FinancialEvidenceLedger.CalculationOperand(
                        "end", "retrieve_nflx_2024", "NFLX", "2024", "paid net membership additions")),
                "percent", "unit", 1, 2);

        assertThat(ledger.calculate(List.of(growth), facts, List.of(evidence)))
                .singleElement().extracting(FinancialEvidenceLedger.VerifiedCalculation::result)
                .isEqualTo(new BigDecimal("364.5"));
    }

    @Test
    void refusesAmbiguousSegmentValuesInsteadOfCalculatingFromFirstMatch() {
        FinancialEvidenceLedger.EvidenceDocument evidence = new FinancialEvidenceLedger.EvidenceDocument(
                "E1", "chunk-E1", "NFLX_2024.html", "NFLX", "2024", "table", "Item 7", "MD&A",
                "| UCAN paid memberships | 80 |\n| EMEA paid memberships | 90 |",
                List.of("retrieve_nflx_2024"));
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact(
                        "F1", "E1", "NFLX", "2022", "paid memberships", "70",
                        new BigDecimal("70"), "count", "thousand", "| Total paid memberships | 70 |"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F2", "E1", "NFLX", "2024", "paid memberships", "80",
                        new BigDecimal("80"), "count", "thousand", "| UCAN paid memberships | 80 |"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F3", "E1", "NFLX", "2024", "paid memberships", "90",
                        new BigDecimal("90"), "count", "thousand", "| EMEA paid memberships | 90 |"));

        assertThat(ledger.calculate(List.of(plan(FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                        operand("start", "NFLX", "2022", "paid memberships"),
                        operand("end", "NFLX", "2024", "paid memberships"))), facts, List.of(evidence)))
                .isEmpty();
    }

    private FinancialEvidenceLedger.CalculationPlan plan(
            FinancialEvidenceLedger.CalculationOperator operator,
            FinancialEvidenceLedger.CalculationOperand... operands) {
        String unit = switch (operator) {
            case RATIO -> "times";
            case PERCENT_CHANGE, MARGIN, CAGR -> "percent";
            case COUNT -> "count";
            default -> "usd";
        };
        String scale = Set.of(FinancialEvidenceLedger.CalculationOperator.RATIO,
                FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                FinancialEvidenceLedger.CalculationOperator.MARGIN,
                FinancialEvidenceLedger.CalculationOperator.COUNT,
                FinancialEvidenceLedger.CalculationOperator.CAGR).contains(operator) ? "unit" : "";
        return new FinancialEvidenceLedger.CalculationPlan(
                "test_calculation", operator, List.of(operands), unit, scale, 4, 0);
    }

    private FinancialEvidenceLedger.CalculationOperand operand(
            String role, String company, String year, String metric) {
        return new FinancialEvidenceLedger.CalculationOperand(role, "", company, year, metric);
    }

    private FinancialEvidenceLedger.EvidenceDocument document(String id, String source, String company,
                                                                String year, String content) {
        return new FinancialEvidenceLedger.EvidenceDocument(
                id, "chunk-" + id, source, company, year, "table", "Item 8", "Statements", content, List.of());
    }

    private FinancialEvidenceLedger.FinancialFact fact(String id, String value, String scale) {
        return new FinancialEvidenceLedger.FinancialFact(
                id, "E1", "AAPL", "2024", "net sales", value,
                new java.math.BigDecimal(value), "usd", scale, value);
    }

    private FinancialEvidenceLedger.FinancialFact ageFact(String id, String year, String value) {
        return new FinancialEvidenceLedger.FinancialFact(
                id, "E" + id.substring(1), "LIN", year, "age", value,
                new java.math.BigDecimal(value), "count", "unit", value);
    }

    private FinancialEvidenceLedger.FinancialFact cashFact(String id, String company, String metric, String value) {
        return new FinancialEvidenceLedger.FinancialFact(
                id, "E" + id.substring(1), company, "2024", metric, value,
                new java.math.BigDecimal(value), "usd", "million", value);
    }
}
