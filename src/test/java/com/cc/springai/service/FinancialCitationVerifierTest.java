package com.cc.springai.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialCitationVerifierTest {

    private final FinancialCitationVerifier verifier = new FinancialCitationVerifier();

    @Test
    void acceptsKnownEvidenceAndCalculationReferences() {
        FinancialCitationVerifier.Audit audit = verifier.verify(
                "AAPL net sales increased from $100 million in 2023 [E1] to $120 million in 2024 [E2]. "
                        + "The increase was 20% [C1][E1][E2].\nSources: [E1] AAPL 2023; [E2] AAPL 2024",
                ledger());

        assertThat(audit.valid()).isTrue();
        assertThat(audit.issues()).isEmpty();
    }

    @Test
    void rejectsUnknownEvidenceReference() {
        FinancialCitationVerifier.Audit audit = verifier.verify(
                "AAPL 2024 net sales were $120 million [E99].", ledger());

        assertThat(audit.valid()).isFalse();
        assertThat(audit.issues()).extracting(FinancialCitationVerifier.Issue::code)
                .contains("unknown_reference");
    }

    @Test
    void rejectsUncitedNumericClaim() {
        FinancialCitationVerifier.Audit audit = verifier.verify(
                "Net sales increased by 20%.\nSources: [E1] AAPL", ledger());

        assertThat(audit.valid()).isFalse();
        assertThat(audit.issues()).extracting(FinancialCitationVerifier.Issue::code)
                .contains("uncited_numeric_claim", "missing_calculation");
    }

    @Test
    void parsesGroupedModelReferences() {
        FinancialCitationVerifier.Audit audit = verifier.verify(
                "AAPL FY2024 net sales were $120 million [F2；E2].", ledger());

        assertThat(audit.issues()).extracting(FinancialCitationVerifier.Issue::code)
                .containsExactly("missing_calculation");
    }

    @Test
    void doesNotSplitDecimalCalculationIntoAnUncitedSentence() {
        FinancialCitationVerifier.Audit audit = verifier.verify(
                "The percentage change was 2.022% [C1][E1][E2].", ledger());

        assertThat(audit.valid()).isTrue();
    }

    @Test
    void acceptsPriorYearColumnFromCurrentYearEvidence() {
        FinancialEvidenceLedger.EvidenceDocument document = new FinancialEvidenceLedger.EvidenceDocument(
                "E1", "c1", "AAPL_2024.html", "AAPL", "2024", "table", "Item 8", "Statements",
                "Total net sales: 2024 $120 million; 2023 $100 million.", List.of("retrieve"));
        FinancialEvidenceLedger.FinancialFact fact = new FinancialEvidenceLedger.FinancialFact(
                "F1", "E1", "AAPL", "2024", "change from 2023 to 2024", "20%",
                new BigDecimal("20"), "percent", "unit", "2024 compared with 2023");
        FinancialEvidenceLedger.Ledger crossYearLedger = new FinancialEvidenceLedger.Ledger(
                List.of(document), List.of(fact), List.of());

        FinancialCitationVerifier.Audit audit = verifier.verify(
                "The change from 2023 to 2024 was 20% [F1][E1].", crossYearLedger);

        assertThat(audit.valid()).isTrue();
    }

    @Test
    void rejectsModelArithmeticWithoutVerifiedCalculationReference() {
        FinancialEvidenceLedger.Ledger factsOnly = new FinancialEvidenceLedger.Ledger(
                ledger().evidence(), ledger().facts(), List.of());

        FinancialCitationVerifier.Audit audit = verifier.verify(
                "The increase was calculated as (120 - 100) / 100 = 20% [E1][E2].", factsOnly);

        assertThat(audit.issues()).extracting(FinancialCitationVerifier.Issue::code)
                .contains("unverified_calculation");
        String safe = verifier.enforce(
                "The increase was calculated as (120 - 100) / 100 = 20% [E1][E2].", factsOnly, true);
        assertThat(safe).startsWith("已核验事实：").doesNotContain("calculated as");
        assertThat(verifier.verify(safe, factsOnly).valid()).isTrue();
    }

    @Test
    void strictModeFallsBackToVerifiedLedger() {
        String result = verifier.enforce("Revenue was $999 million [E99].", ledger(), true);

        assertThat(result).startsWith("已核验事实：");
        assertThat(result).doesNotContain("Revenue was $999 million");
        assertThat(result).contains("[F1][E1]", "[C1][E1][E2]");
        assertThat(verifier.verify(result, ledger()).valid()).isTrue();
    }

    private FinancialEvidenceLedger.Ledger ledger() {
        List<FinancialEvidenceLedger.EvidenceDocument> evidence = List.of(
                new FinancialEvidenceLedger.EvidenceDocument("E1", "c1", "AAPL_2023.html", "AAPL", "2023", "table", "Item 8", "Statements", "Net sales were $100 million.", List.of("retrieve_2023")),
                new FinancialEvidenceLedger.EvidenceDocument("E2", "c2", "AAPL_2024.html", "AAPL", "2024", "table", "Item 8", "Statements", "Net sales were $120 million.", List.of("retrieve_2024")));
        List<FinancialEvidenceLedger.FinancialFact> facts = List.of(
                new FinancialEvidenceLedger.FinancialFact("F1", "E1", "AAPL", "2023", "net sales", "$100 million", new BigDecimal("100"), "usd", "million", "Net sales were $100 million."),
                new FinancialEvidenceLedger.FinancialFact("F2", "E2", "AAPL", "2024", "net sales", "$120 million", new BigDecimal("120"), "usd", "million", "Net sales were $120 million."));
        List<FinancialEvidenceLedger.VerifiedCalculation> calculations = List.of(
                new FinancialEvidenceLedger.VerifiedCalculation("C1", "percentage_change", "(F2-F1)/F1*100", new BigDecimal("20"), "percent", "unit", List.of("F1", "F2")));
        return new FinancialEvidenceLedger.Ledger(evidence, facts, calculations);
    }
}
