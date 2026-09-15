package com.cc.springai.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final Pattern REQUESTED_FISCAL_VALUE = Pattern.compile(
            "(?is)what was.{0,120}?fiscal(?: year)?\\s*(20\\d{2})");
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
            Set<String> sentenceCalculations = references(sentence, 'C');
            if (hasDerivedCalculation(sentence) && sentenceCalculations.isEmpty()) {
                issues.add(new Issue("unverified_calculation", "计算结论缺少确定性计算引用 [C#]", sentence.strip()));
            }
            // A verified [C#] already traces through its source facts to [E#]. Requiring a repeated
            // evidence marker on the same line caused complete trend answers to be replaced by a
            // fallback that rendered only the first calculation.
            boolean groundedCalculation = !sentenceCalculations.isEmpty()
                    && validCalculations.containsAll(sentenceCalculations);
            if (hasNumericClaim(sentence) && sentenceEvidence.isEmpty()
                    && !groundedCalculation && !isSourceHeading(sentence)) {
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
        return enforce(answer, ledger, strict, "");
    }

    String enforce(String answer, FinancialEvidenceLedger.Ledger ledger, boolean strict, String question) {
        Audit audit = verify(answer, ledger);
        if (audit.valid()) {
            return answer;
        }
        String salvaged = removeUnsupportedLines(answer, audit);
        if (!salvaged.equals(answer) && verify(salvaged, ledger).valid()) {
            return salvaged;
        }
        if (strict && !ledger.facts().isEmpty()) {
            String calculated = verifiedCalculationFallback(question, ledger, isPredominantlyEnglish(answer));
            if (!calculated.isBlank() && verify(calculated, ledger).valid()) {
                return calculated;
            }
            String concise = verifiedQuestionFallback(question, ledger, isPredominantlyEnglish(answer));
            if (!concise.isBlank() && verify(concise, ledger).valid()) {
                return concise;
            }
            return verifiedFallback(ledger, isPredominantlyEnglish(answer));
        }
        String details = issueDetails(audit);
        if (!strict) {
            return answer.stripTrailing() + "\n\n> 引用校验警告\n" + details;
        }
        return "当前回答未通过引用校验，因此未输出未经证实的结论。\n\n校验问题：\n"
                + details + "\n\n请重试，或在问题中补充明确的公司、财年和指标。";
    }

    private String verifiedCalculationFallback(String question, FinancialEvidenceLedger.Ledger ledger,
                                               boolean english) {
        if (!english || question == null || question.isBlank() || ledger.calculations().isEmpty()) {
            return "";
        }
        FinancialEvidenceLedger.VerifiedCalculation calculation = ledger.calculations().get(0);
        Map<String, FinancialEvidenceLedger.FinancialFact> factsById = ledger.facts().stream()
                .collect(Collectors.toMap(fact -> upper(fact.factId()), Function.identity()));
        List<FinancialEvidenceLedger.FinancialFact> operands = calculation.sourceFactIds().stream()
                .map(id -> factsById.get(upper(id))).filter(java.util.Objects::nonNull).toList();
        if (operands.isEmpty()) {
            return "";
        }
        String normalizedQuestion = question == null ? "" : question.toUpperCase(Locale.ROOT);
        List<FinancialEvidenceLedger.FinancialFact> presentationOrder = operands.stream()
                .sorted(java.util.Comparator.comparingInt(fact -> {
                    int index = normalizedQuestion.indexOf(upper(fact.company()));
                    return index < 0 ? Integer.MAX_VALUE : index;
                })).toList();
        String facts = presentationOrder.stream().map(fact ->
                fact.company() + " FY" + fact.fiscalYear() + " " + fact.metric().toLowerCase(Locale.ROOT)
                        + " was " + fact.rawValue() + " [" + fact.factId() + "][" + fact.evidenceId() + "]")
                .collect(Collectors.joining("; "));

        LinkedHashSet<String> evidenceIds = operands.stream()
                .map(FinancialEvidenceLedger.FinancialFact::evidenceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String evidenceMarkers = evidenceIds.stream().map(id -> "[" + id + "]").collect(Collectors.joining());
        String conclusion;
        if ("multiple".equalsIgnoreCase(calculation.type()) && operands.size() == 2) {
            conclusion = operands.get(0).company() + "'s value was approximately "
                    + calculation.displayResult() + " times larger than " + operands.get(1).company() + "'s";
        } else {
            conclusion = "The " + calculation.type().replace('_', ' ') + " was "
                    + calculation.displayResult() + ("unit".equalsIgnoreCase(calculation.scale())
                    ? " " + calculation.unit() : " " + calculation.scale() + " " + calculation.unit());
        }
        String sources = evidenceIds.stream().map(id -> {
            FinancialEvidenceLedger.EvidenceDocument document = ledger.evidence().stream()
                    .filter(candidate -> id.equalsIgnoreCase(candidate.evidenceId())).findFirst().orElse(null);
            return document == null ? "" : "[" + id + "] " + document.sourceFile();
        }).filter(value -> !value.isBlank()).collect(Collectors.joining("\n"));
        return facts + ". " + conclusion + " [" + calculation.calculationId() + "]"
                + evidenceMarkers + ".\n\nSources:\n" + sources;
    }

    private String verifiedQuestionFallback(String question, FinancialEvidenceLedger.Ledger ledger,
                                             boolean english) {
        if (!english || question == null || question.isBlank()) {
            return "";
        }
        Matcher requested = REQUESTED_FISCAL_VALUE.matcher(question);
        FinancialEvidenceLedger.VerifiedCalculation count = ledger.calculations().stream()
                .filter(calculation -> "count".equalsIgnoreCase(calculation.type())).findFirst().orElse(null);
        if (!requested.find() || count == null) {
            return "";
        }
        String requestedYear = requested.group(1);
        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        FinancialEvidenceLedger.FinancialFact fact = ledger.facts().stream()
                .filter(candidate -> requestedYear.equals(candidate.fiscalYear()))
                .filter(candidate -> lowerQuestion.contains("net income")
                        && candidate.metric().toLowerCase(Locale.ROOT).contains("net income"))
                .findFirst().orElse(null);
        if (fact == null) {
            return "";
        }
        Set<String> countedYears = matches(YEAR, count.expression());
        String countEvidence = ledger.evidence().stream()
                .filter(document -> !countedYears.isEmpty()
                        && countedYears.stream().allMatch(year -> document.content().contains(year)))
                .map(FinancialEvidenceLedger.EvidenceDocument::evidenceId).findFirst().orElse("");
        if (countEvidence.isBlank()) {
            return "";
        }
        String factValue = displayInRequestedScale(fact, lowerQuestion);
        StringBuilder result = new StringBuilder();
        result.append(fact.company()).append(" fiscal ").append(requestedYear).append(' ')
                .append(fact.metric().toLowerCase(Locale.ROOT)).append(" was ").append(factValue)
                .append(" [").append(fact.factId()).append("][").append(fact.evidenceId()).append("]\n")
                .append("The table covers ").append(count.displayResult()).append(" fiscal years (")
                .append(String.join(", ", countedYears)).append(") [").append(count.calculationId())
                .append("][").append(countEvidence).append("]\n\nSources:\n");
        Map<String, FinancialEvidenceLedger.EvidenceDocument> byId = ledger.evidence().stream()
                .collect(Collectors.toMap(document -> upper(document.evidenceId()), Function.identity()));
        for (String id : new LinkedHashSet<>(List.of(fact.evidenceId(), countEvidence))) {
            FinancialEvidenceLedger.EvidenceDocument document = byId.get(upper(id));
            if (document != null) {
                result.append('[').append(id).append("] ").append(document.sourceFile()).append('\n');
            }
        }
        return result.toString().stripTrailing();
    }

    private String displayInRequestedScale(FinancialEvidenceLedger.FinancialFact fact, String lowerQuestion) {
        if (lowerQuestion.contains("billion") && "million".equalsIgnoreCase(fact.scale())) {
            return "$" + fact.value().divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP).toPlainString()
                    + " billion";
        }
        return fact.rawValue();
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
        Set<String> calculationFactIds = ledger.calculations().stream()
                .flatMap(calculation -> calculation.sourceFactIds().stream()).map(this::upper)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<FinancialEvidenceLedger.FinancialFact> fallbackFacts = calculationFactIds.isEmpty()
                ? ledger.facts()
                : ledger.facts().stream().filter(fact -> calculationFactIds.contains(upper(fact.factId()))
                        || fact.metric().toLowerCase(Locale.ROOT).startsWith("total ")).toList();
        StringBuilder answer = new StringBuilder(english ? "Verified facts:\n" : "已核验事实：\n");
        for (FinancialEvidenceLedger.FinancialFact fact : fallbackFacts) {
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
                if (evidenceIds.isEmpty() && "count".equalsIgnoreCase(calculation.type())) {
                    Set<String> calculatedYears = matches(YEAR, calculation.expression());
                    ledger.evidence().stream()
                            .filter(document -> !calculatedYears.isEmpty()
                                    && calculatedYears.stream().allMatch(year -> document.content().contains(year)))
                            .findFirst().map(FinancialEvidenceLedger.EvidenceDocument::evidenceId)
                            .ifPresent(evidenceIds::add);
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
        LinkedHashSet<String> usedEvidenceIds = fallbackFacts.stream()
                .map(FinancialEvidenceLedger.FinancialFact::evidenceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        ledger.calculations().stream().filter(calculation -> "count".equalsIgnoreCase(calculation.type()))
                .flatMap(calculation -> {
                    Set<String> calculatedYears = matches(YEAR, calculation.expression());
                    return ledger.evidence().stream().filter(document -> !calculatedYears.isEmpty()
                            && calculatedYears.stream().allMatch(year -> document.content().contains(year)))
                            .limit(1).map(FinancialEvidenceLedger.EvidenceDocument::evidenceId);
                }).forEach(usedEvidenceIds::add);
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
