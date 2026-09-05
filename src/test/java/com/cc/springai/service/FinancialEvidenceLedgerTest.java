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
    void doesNotCalculateAcrossDifferentScales() {
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                fact("F1", "100", "million"),
                fact("F2", "1.2", "billion"));

        assertThat(ledger.calculate("Compare the change", facts)).isEmpty();
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

    private FinancialEvidenceLedger.EvidenceDocument document(String id, String source, String company,
                                                                String year, String content) {
        return new FinancialEvidenceLedger.EvidenceDocument(
                id, "chunk-" + id, source, company, year, "table", "Item 8", "Statements", content);
    }

    private FinancialEvidenceLedger.FinancialFact fact(String id, String value, String scale) {
        return new FinancialEvidenceLedger.FinancialFact(
                id, "E1", "AAPL", "2024", "net sales", value,
                new java.math.BigDecimal(value), "usd", scale, value);
    }
}
