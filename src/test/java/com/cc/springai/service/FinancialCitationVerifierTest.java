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
    void doesNotTreatFilingIdentifiersAsNumericClaims() {
        FinancialEvidenceLedger.Ledger qualitativeLedger = new FinancialEvidenceLedger.Ledger(
                List.of(
                        new FinancialEvidenceLedger.EvidenceDocument("E1", "c1", "AEP_2024.html", "AEP", "2024",
                                "text", "Item 7", "MD&A", "AEP enumerates risks.", List.of()),
                        new FinancialEvidenceLedger.EvidenceDocument("E2", "c2", "ED_2024.html", "ED", "2024",
                                "text", "Item 7", "MD&A", "ED uses a broad description.", List.of())),
                List.of(), List.of());

        FinancialCitationVerifier.Audit audit = verifier.verify(
                "AEP's FY2024 Item 7 disclaimer enumerates risks [E1]. "
                        + "ED relies on a broader 10-K description [E2].",
                qualitativeLedger);

        assertThat(audit.valid()).isTrue();
        assertThat(audit.issues()).isEmpty();
    }

    @Test
    void stillTreatsARealNumberNearFilingIdentifiersAsNumericClaim() {
        FinancialEvidenceLedger.Ledger qualitativeLedger = new FinancialEvidenceLedger.Ledger(
                ledger().evidence(), List.of(), List.of());

        FinancialCitationVerifier.Audit audit = verifier.verify(
                "In FY2024 Item 7, the disclosed age was 59.", qualitativeLedger);

        assertThat(audit.issues()).extracting(FinancialCitationVerifier.Issue::code)
                .contains("uncited_numeric_claim", "missing_evidence");
    }

    @Test
    void doesNotTreatHyphenatedOrSlashSeparatedProseAsArithmetic() {
        FinancialEvidenceLedger.Ledger qualitativeLedger = new FinancialEvidenceLedger.Ledger(
                ledger().evidence(), List.of(), List.of());

        FinancialCitationVerifier.Audit audit = verifier.verify(
                "The forward-looking statement discusses volume/pricing changes [E1].",
                qualitativeLedger);

        assertThat(audit.valid()).isTrue();
        assertThat(audit.issues()).isEmpty();
    }

    @Test
    void ignoresMarkdownOrderedListNumbers() {
        FinancialEvidenceLedger.Ledger qualitativeLedger = new FinancialEvidenceLedger.Ledger(
                ledger().evidence(), List.of(), List.of());

        FinancialCitationVerifier.Audit audit = verifier.verify(
                "**1. ECL disclosure** [E1]", qualitativeLedger);

        assertThat(audit.valid()).isTrue();
    }

    @Test
    void acceptsOneTrailingCitationForMultiSentenceQuoteOnSameLine() {
        FinancialCitationVerifier.Audit audit = verifier.verify(
                "Sales were $100 million. They increased to $120 million [E1].\nSources: [E1] AAPL",
                ledger());

        assertThat(audit.issues()).extracting(FinancialCitationVerifier.Issue::code)
                .doesNotContain("uncited_numeric_claim");
    }

    @Test
    void doesNotTreatUnableToComputeAsACompletedCalculation() {
        FinancialEvidenceLedger.Ledger qualitativeLedger = new FinancialEvidenceLedger.Ledger(
                ledger().evidence(), List.of(), List.of());

        FinancialCitationVerifier.Audit audit = verifier.verify(
                "The change cannot be computed from the evidence [E1].", qualitativeLedger);

        assertThat(audit.issues()).extracting(FinancialCitationVerifier.Issue::code)
                .doesNotContain("unverified_calculation");
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
        assertThat(safe).startsWith("Verified facts:").doesNotContain("calculated as");
        assertThat(verifier.verify(safe, factsOnly).valid()).isTrue();
    }

    @Test
    void strictModeFallsBackToVerifiedLedger() {
        String result = verifier.enforce("Revenue was $999 million [E99].", ledger(), true);

        assertThat(result).startsWith("Verified facts:");
        assertThat(result).doesNotContain("Revenue was $999 million");
        assertThat(result).contains("[F1][E1]", "[C1][E1][E2]");
        assertThat(verifier.verify(result, ledger()).valid()).isTrue();
    }

    @Test
    void strictFallbackKeepsChineseForChineseGeneratedAnswer() {
        String result = verifier.enforce("收入是 999 百万美元 [E99]。", ledger(), true);

        assertThat(result).startsWith("已核验事实：");
        assertThat(verifier.verify(result, ledger()).valid()).isTrue();
    }

    @Test
    void strictModeDropsOnlyAnUncitedNumericLineFromOtherwiseCitedAnswer() {
        FinancialEvidenceLedger.Ledger qualitativeLedger = new FinancialEvidenceLedger.Ledger(
                ledger().evidence(), List.of(), List.of());
        String generated = """
                AAPL reported the following sales table:
                > Net sales | $100 million
                AAPL's filing describes its sales performance [E1].

                Sources: [E1] AAPL_2023.html
                """;

        String result = verifier.enforce(generated, qualitativeLedger, true);

        assertThat(result).doesNotContain("$100 million");
        assertThat(result).contains("describes its sales performance [E1]");
        assertThat(verifier.verify(result, qualitativeLedger).valid()).isTrue();
    }

    @Test
    void strictModeDropsOnlyCompanyMismatchedLineFromComparison() {
        FinancialEvidenceLedger.Ledger comparisonLedger = new FinancialEvidenceLedger.Ledger(
                List.of(
                        new FinancialEvidenceLedger.EvidenceDocument(
                                "E1", "aep", "AEP_2024.html", "AEP", "2024", "text",
                                "Item 7", "MD&A", "AEP enumerates risk factors.", List.of("retrieve_aep")),
                        new FinancialEvidenceLedger.EvidenceDocument(
                                "E2", "ed", "ED_2024.html", "ED", "2024", "text",
                                "Item 7", "MD&A", "ED describes environmental changes.", List.of("retrieve_ed"))),
                List.of(), List.of());
        String generated = """
                AEP enumerates risk factors [E1].
                ED provides a broader description [E1].
                ED discusses changes in its environment [E2].
                Sources: [E1] AEP; [E2] ED
                """;

        String result = verifier.enforce(generated, comparisonLedger, true);

        assertThat(result).contains("AEP enumerates risk factors [E1].")
                .contains("ED discusses changes in its environment [E2].")
                .doesNotContain("ED provides a broader description [E1].");
        assertThat(verifier.verify(result, comparisonLedger).valid()).isTrue();
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
