package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialEvidenceLedgerTest {

    private final FinancialEvidenceLedger ledger = new FinancialEvidenceLedger(new ObjectMapper());

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
                        """);

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
        FinancialEvidenceLedger.Ledger partial = ledger.build("percentage change from 2022 to 2024", evidence, """
                {"facts":[{"evidenceId":"E1","metric":"revenue","rawValue":"100 million",
                "value":"100","unit":"USD","scale":"million","quote":"Revenue was 100 million."}]}
                """);

        FinancialEvidenceLedger.Ledger augmented = ledger.augment("percentage change from 2022 to 2024", partial, """
                {"facts":[{"evidenceId":"E2","metric":"revenue","rawValue":"150 million",
                "value":"150","unit":"USD","scale":"million","quote":"Revenue was 150 million."}]}
                """);

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
                evidence, "{\"facts\":[]}");

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
                "Compare total cash, cash equivalents, and short-term investments", evidence, "{\"facts\":[]}");

        assertThat(result.facts()).hasSize(2);
        assertThat(result.calculations()).hasSize(1);
        assertThat(result.calculations().get(0).displayResult()).isEqualTo("5132");
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
                evidence, "{\"facts\":[]}");

        assertThat(result.facts()).extracting(FinancialEvidenceLedger.FinancialFact::value)
                .containsExactly(new java.math.BigDecimal("4972"), new java.math.BigDecimal("2354"));
        assertThat(result.calculations()).hasSize(1);
        assertThat(result.calculations().get(0).displayResult()).isEqualTo("2618");
    }

    @Test
    void convertsCompatibleValuesAcrossDifferentScales() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                fact("F1", "100", "million"),
                fact("F2", "1.2", "billion"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations = ledger.calculate("Compare the change", facts);

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).displayResult()).isEqualTo("1.1");
        assertThat(calculations.get(0).scale()).isEqualTo("billion");
    }

    @Test
    void calculatesRatioAcrossDifferentMetricsWithCompatibleUnits() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact(
                        "F1", "E1", "LLY", "2020", "income taxes", "14.33",
                        new java.math.BigDecimal("14.33"), "usd", "million", "14.33"),
                new FinancialEvidenceLedger.FinancialFact(
                        "F2", "E1", "LLY", "2020", "income before income taxes", "100",
                        new java.math.BigDecimal("100"), "usd", "million", "100"));

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations =
                ledger.calculate("income taxes divided by income before income taxes", facts);

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).type()).isEqualTo("ratio");
        assertThat(calculations.get(0).displayResult()).isEqualTo("14.33");
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

        List<FinancialEvidenceLedger.VerifiedCalculation> calculations =
                ledger.calculate("percentage change from 2023 to 2024", facts);

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
                "How did the age change from FY2022 to FY2023, and is it given in FY2024?", facts);

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).displayResult()).isEqualTo("1");
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
                "Compare total cash, cash equivalents, and short-term investments", facts);

        assertThat(calculations).hasSize(1);
        assertThat(calculations.get(0).displayResult()).isEqualTo("5132");
        assertThat(calculations.get(0).sourceFactIds()).containsExactly("F1", "F2");
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
