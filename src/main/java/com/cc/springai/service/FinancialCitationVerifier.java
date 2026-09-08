package com.cc.springai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class FinancialCitationVerifier {

    // Accept [F1][E2] as well as grouped model output such as [F1; E2] or [F1；E2].
    private static final Pattern REFERENCE = Pattern.compile(
            "(?i)(?<![A-Z0-9])(E|F|C)(\\d+)(?![A-Z0-9])");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)20\\d{2}(?!\\d)");
    private static final Pattern NUMERIC_CLAIM = Pattern.compile("(?:[$€£¥]|\\b\\d[\\d,.]*%?\\b)");
    // Filing identifiers describe where a claim appears; they are not financial/numeric claims themselves.
    // Removing them before NUMERIC_CLAIM matching avoids treating "FY2024", "Item 7" and "10-K"
    // as uncited amounts while year/company consistency is still checked separately below.
    private static final Pattern FILING_IDENTIFIER = Pattern.compile(
            "(?i)\\b(?:FY\\s*)?20\\d{2}\\b|\\b(?:Item|Part|Section|Note)\\s+\\d+[A-Z]?(?:\\.\\d+)?\\b|\\b10-[KQ]\\b");
    private static final Pattern DERIVED_CALCULATION = Pattern.compile(
            "(?i)(?:计算得出|按.+计算|calculated|computed|"
                    + "\\([^\\n)]*(?:F\\d+|\\d+(?:\\.\\d+)?)\\s*[-+*/×÷]\\s*"
                    + "(?:F\\d+|\\d+(?:\\.\\d+)?)[^\\n)]*\\))");
    private static final Pattern NEGATED_CALCULATION = Pattern.compile(
            "(?i)\\b(?:cannot|can't|could not|not)\\s+(?:be\\s+)?(?:calculated|computed)\\b|无法(?:计算|确定)");

    Audit verify(String answer, FinancialEvidenceLedger.Ledger ledger) {
        if (answer == null || answer.isBlank()) {
            return new Audit(false, List.of(new Issue("empty_answer", "模型没有生成答案", "")));
        }
        Set<String> validEvidence = ids(ledger.evidence(), FinancialEvidenceLedger.EvidenceDocument::evidenceId);
        Set<String> validFacts = ids(ledger.facts(), FinancialEvidenceLedger.FinancialFact::factId);
        Set<String> validCalculations = ids(ledger.calculations(), FinancialEvidenceLedger.VerifiedCalculation::calculationId);
        List<Issue> issues = new ArrayList<>();
        Set<String> citedEvidence = new LinkedHashSet<>();
        Set<String> citedCalculations = new LinkedHashSet<>();

        Matcher references = REFERENCE.matcher(answer);
        while (references.find()) {
            String id = references.group(1).toUpperCase(Locale.ROOT) + references.group(2);
            Set<String> allowed = switch (id.charAt(0)) {
                case 'E' -> validEvidence;
                case 'F' -> validFacts;
                case 'C' -> validCalculations;
                default -> Set.of();
            };
            if (!allowed.contains(id)) {
                issues.add(new Issue("unknown_reference", "引用了不存在的 " + id,
                        sentenceAt(answer, references.start())));
            } else if (id.startsWith("E")) {
                citedEvidence.add(id);
            } else if (id.startsWith("C")) {
                citedCalculations.add(id);
            }
        }

        if (!validEvidence.isEmpty() && citedEvidence.isEmpty()) {
            issues.add(new Issue("missing_evidence", "答案没有引用任何原始证据 [E#]", ""));
        }
        if (!validCalculations.isEmpty() && citedCalculations.isEmpty()) {
            issues.add(new Issue("missing_calculation", "答案使用了计算场景，但没有标记确定性计算 [C#]", ""));
        }

        Map<String, FinancialEvidenceLedger.EvidenceDocument> documents = ledger.evidence().stream()
                .collect(Collectors.toMap(document -> upper(document.evidenceId()), Function.identity()));
        Map<String, Set<String>> supportedYears = supportedYears(ledger);
        // A citation at the end of a paragraph/quote supports every sentence in that same block.
        // Splitting on punctuation incorrectly rejects multi-sentence SEC quotations with one trailing [E#].
        for (String sentence : answer.split("\\R")) {
            if (sentence.isBlank()) {
                continue;
            }
            Set<String> sentenceEvidence = references(sentence, 'E');
            if (hasDerivedCalculation(sentence) && references(sentence, 'C').isEmpty()) {
                issues.add(new Issue("unverified_calculation", "计算结论缺少确定性计算引用 [C#]", sentence.strip()));
            }
            if (hasNumericClaim(sentence) && sentenceEvidence.isEmpty() && !isSourceHeading(sentence)) {
                issues.add(new Issue("uncited_numeric_claim", "数值结论缺少原始证据 [E#]", sentence.strip()));
                continue;
            }
            if (sentenceEvidence.isEmpty()) {
                continue;
            }
            Set<String> citedCompanies = sentenceEvidence.stream().map(documents::get)
                    .filter(document -> document != null && !document.company().isBlank())
                    .map(document -> upper(document.company())).collect(Collectors.toSet());
            Set<String> mentionedCompanies = documents.values().stream()
                    .map(FinancialEvidenceLedger.EvidenceDocument::company).filter(company -> !company.isBlank())
                    .map(this::upper).filter(company -> containsToken(sentence, company)).collect(Collectors.toSet());
            if (!mentionedCompanies.isEmpty() && !citedCompanies.containsAll(mentionedCompanies)) {
                issues.add(new Issue("company_mismatch", "引用证据与句子中的公司不一致", sentence.strip()));
            }

            Set<String> years = matches(YEAR, sentence);
            if (!years.isEmpty()) {
                Set<String> citedYears = sentenceEvidence.stream()
                        .flatMap(id -> supportedYears.getOrDefault(id, Set.of()).stream()).collect(Collectors.toSet());
                if (!citedYears.containsAll(years)) {
                    issues.add(new Issue("year_mismatch", "引用证据不支持句子中的全部财年", sentence.strip()));
                }
            }
        }
        return new Audit(issues.isEmpty(), List.copyOf(deduplicate(issues)));
    }

    String enforce(String answer, FinancialEvidenceLedger.Ledger ledger, boolean strict) {
        Audit audit = verify(answer, ledger);
        if (audit.valid()) {
            return answer;
        }
        String salvaged = removeUnsupportedLines(answer, audit);
        if (!salvaged.equals(answer) && verify(salvaged, ledger).valid()) {
            return salvaged;
        }
        if (strict && !ledger.facts().isEmpty()) {
            return verifiedFallback(ledger, isPredominantlyEnglish(answer));
        }
        String details = issueDetails(audit);
        if (!strict) {
            return answer.stripTrailing() + "\n\n> 引用校验警告\n" + details;
        }
        return "当前回答未通过引用校验，因此未输出未经证实的结论。\n\n校验问题：\n"
                + details + "\n\n请重试，或在问题中补充明确的公司、财年和指标。";
    }

    private String removeUnsupportedLines(String answer, Audit audit) {
        Set<String> salvageableCodes = Set.of(
                "uncited_numeric_claim", "company_mismatch", "year_mismatch");
        if (audit.issues().isEmpty()
                || audit.issues().stream().anyMatch(issue -> !salvageableCodes.contains(issue.code()))) {
            return answer;
        }
        Set<String> unsafeLines = audit.issues().stream()
                .map(Issue::sentence)
                .filter(sentence -> sentence != null && !sentence.isBlank())
                .map(String::strip)
                .collect(Collectors.toSet());
        if (unsafeLines.isEmpty()) {
            return answer;
        }
        return answer.lines()
                .filter(line -> !unsafeLines.contains(line.strip()))
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    private String verifiedFallback(FinancialEvidenceLedger.Ledger ledger, boolean english) {
        Map<String, FinancialEvidenceLedger.FinancialFact> facts = ledger.facts().stream()
                .collect(Collectors.toMap(fact -> upper(fact.factId()), Function.identity()));
        Map<String, FinancialEvidenceLedger.EvidenceDocument> evidence = ledger.evidence().stream()
                .collect(Collectors.toMap(document -> upper(document.evidenceId()), Function.identity()));
        StringBuilder answer = new StringBuilder(english ? "Verified facts:\n" : "已核验事实：\n");
        for (FinancialEvidenceLedger.FinancialFact fact : ledger.facts()) {
            answer.append("- ").append(fact.company()).append(" FY").append(fact.fiscalYear())
                    .append(" | ").append(fact.metric()).append(" = ").append(fact.rawValue())
                    .append(" [").append(fact.factId()).append("][").append(fact.evidenceId()).append("]\n");
        }
        if (!ledger.calculations().isEmpty()) {
            answer.append(english ? "\nDeterministic calculations:\n" : "\n确定性计算：\n");
            for (FinancialEvidenceLedger.VerifiedCalculation calculation : ledger.calculations()) {
                LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
                for (String factId : calculation.sourceFactIds()) {
                    FinancialEvidenceLedger.FinancialFact fact = facts.get(upper(factId));
                    if (fact != null) {
                        evidenceIds.add(fact.evidenceId());
                    }
                }
                answer.append("- ").append(calculation.type()).append(": ")
                        .append(calculation.expression()).append(" = ").append(calculation.displayResult())
                        .append(' ').append(calculation.scale()).append(' ').append(calculation.unit())
                        .append(" [").append(calculation.calculationId()).append(']');
                evidenceIds.forEach(id -> answer.append('[').append(id).append(']'));
                answer.append('\n');
            }
        }
        answer.append(english ? "\nSources:\n" : "\n来源：\n");
        LinkedHashSet<String> usedEvidenceIds = ledger.facts().stream()
                .map(FinancialEvidenceLedger.FinancialFact::evidenceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String evidenceId : usedEvidenceIds) {
            FinancialEvidenceLedger.EvidenceDocument document = evidence.get(upper(evidenceId));
            if (document == null) {
                continue;
            }
            String section = !document.sectionTitle().isBlank() ? document.sectionTitle() : document.item();
            answer.append('[').append(document.evidenceId()).append("] ")
                    .append(document.sourceFile()).append(" — ").append(section).append('\n');
        }
        return answer.toString().stripTrailing();
    }

    private boolean isPredominantlyEnglish(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        long latin = value.codePoints().filter(codePoint ->
                (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z')).count();
        long cjk = value.codePoints().filter(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF).count();
        return latin > cjk;
    }

    private String issueDetails(Audit audit) {
        StringBuilder details = new StringBuilder();
        audit.issues().stream().limit(5)
                .forEach(issue -> details.append("- ").append(issue.message()).append('\n'));
        return details.toString().trim();
    }

    private Map<String, Set<String>> supportedYears(FinancialEvidenceLedger.Ledger ledger) {
        Map<String, Set<String>> result = ledger.evidence().stream().collect(Collectors.toMap(
                document -> upper(document.evidenceId()),
                document -> {
                    LinkedHashSet<String> years = new LinkedHashSet<>(matches(YEAR, document.content()));
                    if (!document.fiscalYear().isBlank()) {
                        years.add(document.fiscalYear());
                    }
                    return years;
                }));
        for (FinancialEvidenceLedger.FinancialFact fact : ledger.facts()) {
            Set<String> years = result.computeIfAbsent(upper(fact.evidenceId()), ignored -> new LinkedHashSet<>());
            if (!fact.fiscalYear().isBlank()) {
                years.add(fact.fiscalYear());
            }
            years.addAll(matches(YEAR, fact.metric()));
            years.addAll(matches(YEAR, fact.quote()));
        }
        return result;
    }

    private Set<String> references(String sentence, char type) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(sentence);
        while (matcher.find()) {
            if (Character.toUpperCase(matcher.group(1).charAt(0)) == type) {
                result.add(Character.toString(type) + matcher.group(2));
            }
        }
        return result;
    }

    private <T> Set<String> ids(List<T> values, Function<T, String> mapper) {
        return values.stream().map(mapper).map(this::upper)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> matches(Pattern pattern, String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private List<Issue> deduplicate(List<Issue> issues) {
        Map<String, Issue> unique = issues.stream().collect(Collectors.toMap(
                issue -> issue.code() + "|" + issue.sentence(), Function.identity(), (left, right) -> left,
                LinkedHashMap::new));
        return new ArrayList<>(unique.values());
    }

    private boolean containsToken(String sentence, String token) {
        return Pattern.compile("(?i)(?<![A-Z0-9-])" + Pattern.quote(token) + "(?![A-Z0-9-])")
                .matcher(sentence).find();
    }

    private boolean isSourceHeading(String sentence) {
        String normalized = sentence.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("来源") || normalized.equals("来源：") || normalized.equals("sources")
                || normalized.startsWith("sources:");
    }

    private boolean hasNumericClaim(String sentence) {
        String withoutReferences = REFERENCE.matcher(sentence).replaceAll("")
                .replaceFirst("^\\s*(?:[-*#>]\\s*)*(?:\\*{0,2})?\\d+[.)、](?:\\*{0,2})?\\s*", "");
        withoutReferences = FILING_IDENTIFIER.matcher(withoutReferences).replaceAll("");
        return NUMERIC_CLAIM.matcher(withoutReferences).find();
    }

    private boolean hasDerivedCalculation(String sentence) {
        return !NEGATED_CALCULATION.matcher(sentence).find() && DERIVED_CALCULATION.matcher(sentence).find();
    }

    private String sentenceAt(String answer, int position) {
        int start = Math.max(answer.lastIndexOf('\n', Math.max(0, position - 1)), 0);
        int end = answer.indexOf('\n', position);
        return answer.substring(start, end < 0 ? answer.length() : end).strip();
    }

    private String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    record Issue(String code, String message, String sentence) {
    }

    record Audit(boolean valid, List<Issue> issues) {
    }
}
