package com.cc.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FinancialEvidenceLedger {

    private static final int MAX_FACTS = 40;
    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CALCULATION_YEAR_PAIR = Pattern.compile(
            "(?i)\\b(?:from|between)\\s+(?:FY\\s*)?(20\\d{2})\\s+(?:to|and)\\s+(?:FY\\s*)?(20\\d{2})\\b");
    private static final Pattern NUMERIC_VALUE = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern INCLUDED_YEAR_LIST = Pattern.compile(
            "(?i)(?:includes?|included)\\s+(?:fiscal\\s+)?years?\\s+((?:20\\d{2}\\D{0,12}){2,}20\\d{2})");
    private static final Pattern FOUR_DIGIT_YEAR = Pattern.compile("\\b20\\d{2}\\b");
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\|\\s*([^|]+?)\\s*\\|([^\\r\\n]+)$");
    private static final Pattern ROW_NUMBER = Pattern.compile("\\(?\\d[\\d,]*(?:\\.\\d+)?\\)?");
    private static final Pattern NET_INCOME_PREMISE = Pattern.compile(
            "(?i)net income for fiscal(?: year)?\\s*(20\\d{2})\\s+is\\s+\\$?([\\d,.]+)\\s+(million|billion)");

    private final ObjectMapper objectMapper;

    FinancialEvidenceLedger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Ledger build(String question, List<EvidenceDocument> documents, String extractionJson) {
        List<FinancialFact> facts = mergeFacts(
                deterministicFacts(question, documents), parseVerifiedFacts(documents, extractionJson));
        List<EvidenceDocument> evidence = List.copyOf(documents);
        return new Ledger(evidence, facts, calculateAll(question, facts, evidence));
    }

    Ledger augment(String question, Ledger existing, String extractionJson) {
        List<FinancialFact> merged = mergeFacts(
                deterministicFacts(question, existing.evidence()),
                mergeFacts(existing.facts(), parseVerifiedFacts(existing.evidence(), extractionJson)));
        List<FinancialFact> renumbered = new ArrayList<>();
        for (FinancialFact fact : merged) {
            renumbered.add(new FinancialFact(
                    "F" + (renumbered.size() + 1), fact.evidenceId(), fact.company(), fact.fiscalYear(), fact.metric(),
                    fact.rawValue(), fact.value(), fact.unit(), fact.scale(), fact.quote()));
        }
        List<FinancialFact> facts = List.copyOf(renumbered);
        return new Ledger(existing.evidence(), facts, calculateAll(question, facts, existing.evidence()));
    }

    private List<FinancialFact> mergeFacts(List<FinancialFact> preferred, List<FinancialFact> additional) {
        List<FinancialFact> merged = new ArrayList<>(preferred);
        for (FinancialFact candidate : additional) {
            boolean conflictsWithPreferred = preferred.stream().anyMatch(fact ->
                    fact.company().equalsIgnoreCase(candidate.company())
                            && fact.fiscalYear().equals(candidate.fiscalYear())
                            && canonicalMetric(fact.metric()).equals(canonicalMetric(candidate.metric()))
                            && fact.value().compareTo(candidate.value()) != 0);
            if (!conflictsWithPreferred && merged.stream().noneMatch(fact -> sameFact(fact, candidate))) {
                merged.add(candidate);
            }
        }
        List<FinancialFact> renumbered = new ArrayList<>();
        for (FinancialFact fact : merged) {
            renumbered.add(new FinancialFact(
                    "F" + (renumbered.size() + 1), fact.evidenceId(), fact.company(), fact.fiscalYear(), fact.metric(),
                    fact.rawValue(), fact.value(), fact.unit(), fact.scale(), fact.quote()));
        }
        return List.copyOf(renumbered);
    }

    private List<FinancialFact> deterministicFacts(String question, List<EvidenceDocument> documents) {
        List<FinancialFact> facts = new ArrayList<>();
        String lower = normalize(question).toLowerCase(Locale.ROOT);
        if (lower.contains("total cash") && lower.contains("short-term investments")) {
            for (EvidenceDocument document : documents) {
                addFirstTableRowFact(facts, document, "cash and cash equivalents");
                addFirstTableRowFact(facts, document, "short-term investments");
                addFirstTableRowFact(facts, document, "total cash, cash equivalents, and short-term investments");
            }
        }
        Matcher premise = NET_INCOME_PREMISE.matcher(question);
        if (premise.find()) {
            String currentYear = premise.group(1);
            BigDecimal stated = parseDecimal(premise.group(2));
            BigDecimal statedBase = stated == null ? null : stated.multiply(scaleMultiplier(premise.group(3)), MATH_CONTEXT);
            RowCandidate best = null;
            for (EvidenceDocument document : documents) {
                Matcher rows = TABLE_ROW.matcher(document.content());
                while (rows.find()) {
                    String metric = normalize(rows.group(1));
                    if (!canonicalMetric(metric).equals("net income") || metric.toLowerCase(Locale.ROOT).contains("noncontrolling")) {
                        continue;
                    }
                    List<BigDecimal> values = rowValues(rows.group(2));
                    if (values.size() < 2 || statedBase == null) {
                        continue;
                    }
                    String scale = inferScale(document.content());
                    BigDecimal distance = values.get(0).multiply(scaleMultiplier(scale), MATH_CONTEXT)
                            .subtract(statedBase, MATH_CONTEXT).abs();
                    if (best == null || distance.compareTo(best.distance()) < 0) {
                        best = new RowCandidate(document, metric, rows.group(), values, scale, distance);
                    }
                }
            }
            if (best != null && best.distance().compareTo(statedBase.multiply(new BigDecimal("0.20"))) <= 0) {
                facts.add(tableFact(best.document(), best.metric(), currentYear, best.values().get(0), best.scale(), best.quote()));
                facts.add(tableFact(best.document(), best.metric(), Integer.toString(Integer.parseInt(currentYear) - 1),
                        best.values().get(1), best.scale(), best.quote()));
            }
        }
        return mergeFacts(List.of(), facts);
    }

    private void addFirstTableRowFact(List<FinancialFact> facts, EvidenceDocument document, String requestedMetric) {
        Matcher rows = TABLE_ROW.matcher(document.content());
        while (rows.find()) {
            String metric = normalize(rows.group(1));
            if (!metric.equalsIgnoreCase(requestedMetric)) {
                continue;
            }
            List<BigDecimal> values = rowValues(rows.group(2));
            if (!values.isEmpty()) {
                facts.add(tableFact(document, metric, document.fiscalYear(), values.get(0),
                        inferScale(document.content()), rows.group()));
            }
            return;
        }
    }

    private FinancialFact tableFact(EvidenceDocument document, String metric, String fiscalYear,
                                    BigDecimal value, String scale, String quote) {
        return new FinancialFact("", document.evidenceId(), document.company(), fiscalYear, metric,
                "$" + value.toPlainString() + " " + scale, value, "usd", scale, quote);
    }

    private List<BigDecimal> rowValues(String rowTail) {
        List<BigDecimal> values = new ArrayList<>();
        Matcher matcher = ROW_NUMBER.matcher(rowTail);
        while (matcher.find()) {
            BigDecimal value = parseDecimal(matcher.group());
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private String inferScale(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("in billions") || lower.contains("$ in billions")) {
            return "billion";
        }
        if (lower.contains("in thousands") || lower.contains("$ in thousands")) {
            return "thousand";
        }
        return "million";
    }

    Ledger evidenceOnly(List<EvidenceDocument> documents) {
        return new Ledger(List.copyOf(documents), List.of(), List.of());
    }

    String extractionPrompt(String question, List<EvidenceDocument> documents) {
        StringBuilder evidence = new StringBuilder();
        for (EvidenceDocument document : documents) {
            evidence.append('[').append(document.evidenceId()).append("] ")
                    .append("company=").append(document.company())
                    .append(" | fiscalYear=").append(document.fiscalYear())
                    .append(" | source=").append(document.sourceFile())
                    .append(" | modality=").append(document.modality())
                    .append('\n').append(document.content()).append("\n\n");
        }
        return """
                Question:
                %s

                Retrieved evidence:
                %s

                Extract only the numerical facts directly needed to answer the question. Return strict JSON only:
                {
                  "facts": [
                    {
                      "evidenceId": "E1",
                      "company": "SEC ticker",
                      "fiscalYear": "2024",
                      "metric": "canonical metric name",
                      "rawValue": "$12.3 million",
                      "value": "12.3",
                      "unit": "USD | percent | count | other",
                      "scale": "unit | thousand | million | billion",
                      "quote": "an exact continuous quote copied from that evidence"
                    }
                  ]
                }

                Rules:
                - Do not calculate and do not infer missing values.
                - value is the decimal in the displayed scale: $12.3 million => value 12.3, scale million.
                - Parenthesized financial values are negative.
                - quote must be copied exactly from the selected evidence and contain the rawValue number; currency
                  and scale may come from the same table's header when they are not repeated in the selected row.
                - Include only facts whose company, fiscal year, metric and period can be determined from the evidence.
                - Extract every operand needed by every numerical clause, not merely the first matching or latest value.
                - When a table row contains values for multiple requested fiscal years, emit one fact per requested
                  year, reuse the exact row quote, and set fiscalYear to the column period rather than the filing year.
                - For a requested total, emit the directly reported total when present; otherwise emit every named
                  component needed to calculate it. Never emit only one component of a requested total.
                - For a trend/change, emit facts in chronological order. For a ratio, emit numerator before denominator.
                - Return {"facts":[]} when the evidence is insufficient.
                """.formatted(question, evidence.toString().trim());
    }

    private List<FinancialFact> parseVerifiedFacts(List<EvidenceDocument> documents, String extractionJson) {
        if (extractionJson == null || extractionJson.isBlank() || documents.isEmpty()) {
            return List.of();
        }
        Map<String, EvidenceDocument> byId = new LinkedHashMap<>();
        documents.forEach(document -> byId.put(document.evidenceId().toUpperCase(Locale.ROOT), document));
        List<FinancialFact> facts = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(extractionJson));
            JsonNode nodes = root.path("facts");
            if (!nodes.isArray()) {
                return List.of();
            }
            for (JsonNode node : nodes) {
                String evidenceId = text(node, "evidenceId").toUpperCase(Locale.ROOT);
                EvidenceDocument document = byId.get(evidenceId);
                String metric = text(node, "metric");
                String quote = text(node, "quote");
                String rawValue = text(node, "rawValue");
                BigDecimal value = parseRawDecimal(rawValue);
                if (document == null || metric.isBlank() || quote.isBlank() || rawValue.isBlank() || value == null) {
                    continue;
                }
                String normalizedDocument = normalize(document.content());
                String normalizedQuote = normalize(quote);
                if (normalizedQuote.isBlank() || !normalizedDocument.contains(normalizedQuote)
                        || !rawValueSupportedByQuote(rawValue, quote)) {
                    continue;
                }
                String company = defaultIfBlank(text(node, "company"), document.company());
                String fiscalYear = defaultIfBlank(text(node, "fiscalYear"), document.fiscalYear());
                FinancialFact candidate = new FinancialFact(
                        "F" + (facts.size() + 1), evidenceId, company, fiscalYear, metric,
                        rawValue, value, normalizeUnit(text(node, "unit")), normalizeScale(text(node, "scale")), quote);
                boolean duplicate = facts.stream().anyMatch(existing -> sameFact(existing, candidate));
                if (!duplicate) {
                    facts.add(candidate);
                }
                if (facts.size() >= MAX_FACTS) {
                    break;
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(facts);
    }

    List<VerifiedCalculation> calculate(String question, List<FinancialFact> facts) {
        if (facts.size() < 2) {
            return List.of();
        }
        String normalizedQuestion = normalize(question).toLowerCase(Locale.ROOT);
        CalculationKind kind = inferCalculation(normalizedQuestion);
        if (kind == CalculationKind.NONE) {
            return List.of();
        }
        boolean requireSameMetric = kind == CalculationKind.DIFFERENCE || kind == CalculationKind.PERCENTAGE_CHANGE;
        List<FinancialFact> operands = bestCompatibleGroup(facts, requireSameMetric);
        operands = selectRequestedYearPair(normalizedQuestion, operands).stream()
                .sorted((left, right) -> left.fiscalYear().compareTo(right.fiscalYear()))
                .toList();
        if (operands.size() < 2) {
            return List.of();
        }
        return switch (kind) {
            case DIFFERENCE -> differenceCalculations(operands, wantsPercentage(normalizedQuestion));
            case PERCENTAGE_CHANGE -> percentageChange(operands);
            case RATIO -> ratio(operands);
            case SUM -> sum(operands);
            case NONE -> List.of();
        };
    }

    private List<VerifiedCalculation> calculateAll(String question, List<FinancialFact> facts,
                                                   List<EvidenceDocument> documents) {
        List<VerifiedCalculation> calculations = new ArrayList<>(calculate(question, facts));
        yearCountCalculation(question, documents, calculations.size() + 1).ifPresent(calculations::add);
        return List.copyOf(calculations);
    }

    private java.util.Optional<VerifiedCalculation> yearCountCalculation(
            String question, List<EvidenceDocument> documents, int calculationNumber) {
        String normalized = normalize(question);
        if (!normalized.toLowerCase(Locale.ROOT).matches("(?s).*how many(?: fiscal)? years.*")) {
            return java.util.Optional.empty();
        }
        Matcher listMatcher = INCLUDED_YEAR_LIST.matcher(normalized);
        if (!listMatcher.find()) {
            return java.util.Optional.empty();
        }
        LinkedHashMap<String, Boolean> years = new LinkedHashMap<>();
        Matcher yearMatcher = FOUR_DIGIT_YEAR.matcher(listMatcher.group(1));
        while (yearMatcher.find()) {
            years.put(yearMatcher.group(), Boolean.TRUE);
        }
        if (years.size() < 2 || documents.stream().noneMatch(document ->
                years.keySet().stream().allMatch(year -> document.content().contains(year)))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new VerifiedCalculation(
                "C" + calculationNumber, "count", "count(" + String.join(", ", years.keySet()) + ")",
                BigDecimal.valueOf(years.size()), "count", "unit", List.of()));
    }

    private List<FinancialFact> selectRequestedYearPair(String question, List<FinancialFact> operands) {
        Matcher matcher = CALCULATION_YEAR_PAIR.matcher(question);
        if (!matcher.find()) {
            return operands;
        }
        String firstYear = matcher.group(1);
        String secondYear = matcher.group(2);
        FinancialFact first = operands.stream().filter(fact -> firstYear.equals(fact.fiscalYear())).findFirst().orElse(null);
        FinancialFact second = operands.stream().filter(fact -> secondYear.equals(fact.fiscalYear())).findFirst().orElse(null);
        return first == null || second == null ? operands : List.of(first, second);
    }

    private List<VerifiedCalculation> differenceCalculations(List<FinancialFact> operands, boolean includePercentage) {
        List<VerifiedCalculation> calculations = new ArrayList<>();
        FinancialFact first = operands.get(0);
        FinancialFact last = operands.get(operands.size() - 1);
        BigDecimal differenceBase = baseValue(last).subtract(baseValue(first), MATH_CONTEXT);
        BigDecimal difference = displayedValue(differenceBase, last.scale());
        calculations.add(new VerifiedCalculation("C1", "difference",
                last.factId() + " - " + first.factId(), difference, first.unit(), last.scale(),
                List.of(first.factId(), last.factId())));
        if (includePercentage && first.value().compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal percentage = differenceBase.divide(baseValue(first), MATH_CONTEXT).multiply(BigDecimal.valueOf(100));
            calculations.add(new VerifiedCalculation("C2", "percentage_change",
                    "(" + last.factId() + " - " + first.factId() + ") / " + first.factId() + " × 100",
                    percentage, "percent", "unit", List.of(first.factId(), last.factId())));
        }
        return List.copyOf(calculations);
    }

    private List<VerifiedCalculation> percentageChange(List<FinancialFact> operands) {
        FinancialFact first = operands.get(0);
        FinancialFact last = operands.get(operands.size() - 1);
        BigDecimal firstBase = baseValue(first);
        if (firstBase.compareTo(BigDecimal.ZERO) == 0) {
            return List.of();
        }
        BigDecimal result = baseValue(last).subtract(firstBase, MATH_CONTEXT)
                .divide(firstBase, MATH_CONTEXT).multiply(BigDecimal.valueOf(100));
        return List.of(new VerifiedCalculation("C1", "percentage_change",
                "(" + last.factId() + " - " + first.factId() + ") / " + first.factId() + " × 100",
                result, "percent", "unit", List.of(first.factId(), last.factId())));
    }

    private List<VerifiedCalculation> ratio(List<FinancialFact> operands) {
        FinancialFact numerator = operands.get(0);
        FinancialFact denominator = operands.get(1);
        BigDecimal denominatorBase = baseValue(denominator);
        if (denominatorBase.compareTo(BigDecimal.ZERO) == 0) {
            return List.of();
        }
        BigDecimal result = baseValue(numerator).divide(denominatorBase, MATH_CONTEXT)
                .multiply(BigDecimal.valueOf(100));
        return List.of(new VerifiedCalculation("C1", "ratio",
                numerator.factId() + " / " + denominator.factId() + " × 100",
                result, "percent", "unit", List.of(numerator.factId(), denominator.factId())));
    }

    private List<VerifiedCalculation> sum(List<FinancialFact> operands) {
        Map<String, List<FinancialFact>> groups = new LinkedHashMap<>();
        for (FinancialFact fact : operands) {
            String key = normalize(fact.company()).toUpperCase(Locale.ROOT) + "|" + fact.fiscalYear() + "|" + fact.unit();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
        }
        List<VerifiedCalculation> results = new ArrayList<>();
        for (List<FinancialFact> group : groups.values()) {
            if (group.size() < 2 || group.stream().anyMatch(fact -> canonicalMetric(fact.metric()).startsWith("total "))) {
                continue;
            }
            String outputScale = group.stream().map(FinancialFact::scale)
                    .max((left, right) -> scaleMultiplier(left).compareTo(scaleMultiplier(right))).orElse("unit");
            BigDecimal baseResult = group.stream().map(this::baseValue)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MATH_CONTEXT));
            List<String> sourceFactIds = group.stream().map(FinancialFact::factId).toList();
            results.add(new VerifiedCalculation("C" + (results.size() + 1), "sum",
                    String.join(" + ", sourceFactIds), displayedValue(baseResult, outputScale),
                    group.get(0).unit(), outputScale, sourceFactIds));
        }
        return List.copyOf(results);
    }

    private List<FinancialFact> bestCompatibleGroup(List<FinancialFact> facts, boolean requireSameMetric) {
        Map<String, List<FinancialFact>> groups = new LinkedHashMap<>();
        for (FinancialFact fact : facts) {
            String metricKey = requireSameMetric ? canonicalMetric(fact.metric()) + "|" : "";
            String key = metricKey + fact.unit();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fact);
        }
        return groups.values().stream().filter(group -> group.size() >= 2)
                .max((left, right) -> Integer.compare(left.size(), right.size()))
                .map(List::copyOf).orElse(List.of());
    }

    private CalculationKind inferCalculation(String question) {
        if (containsAny(question, "percentage change", "percent change", "growth rate", "增长率", "百分比变化")) {
            return CalculationKind.PERCENTAGE_CHANGE;
        }
        if (containsAny(question, "divided by", "ratio of", "占比", "比率")) {
            return CalculationKind.RATIO;
        }
        if (containsAny(question, "combined", "sum of", "total of", "total cash", "合计", "总和")) {
            return CalculationKind.SUM;
        }
        if (containsAny(question, "difference", "change", "increase", "decrease", "compare", "变化", "增加", "减少", "相比", "差额", "对比")) {
            return CalculationKind.DIFFERENCE;
        }
        return CalculationKind.NONE;
    }

    private boolean wantsPercentage(String question) {
        return containsAny(question, "percent", "percentage", "%", "百分比", "增长率");
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameFact(FinancialFact left, FinancialFact right) {
        return canonicalMetric(left.metric()).equals(canonicalMetric(right.metric()))
                && left.value().compareTo(right.value()) == 0
                && left.company().equalsIgnoreCase(right.company())
                && left.fiscalYear().equals(right.fiscalYear());
    }

    private BigDecimal parseRawDecimal(String rawValue) {
        Matcher matcher = NUMERIC_VALUE.matcher(rawValue == null ? "" : rawValue);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal value = parseDecimal(matcher.group());
        String normalized = normalize(rawValue);
        return normalized.startsWith("(") && normalized.endsWith(")") && value != null && value.signum() > 0
                ? value.negate() : value;
    }

    private boolean rawValueSupportedByQuote(String rawValue, String quote) {
        Matcher rawMatcher = NUMERIC_VALUE.matcher(rawValue);
        if (!rawMatcher.find()) {
            return false;
        }
        BigDecimal rawNumber = parseDecimal(rawMatcher.group());
        if (rawNumber == null) {
            return false;
        }
        Matcher quoteMatcher = NUMERIC_VALUE.matcher(quote);
        while (quoteMatcher.find()) {
            BigDecimal quoteNumber = parseDecimal(quoteMatcher.group());
            if (quoteNumber != null && quoteNumber.compareTo(rawNumber) == 0) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace(",", "");
        boolean negative = normalized.startsWith("(") && normalized.endsWith(")");
        normalized = normalized.replaceAll("[^0-9+\\-.]", "");
        if (normalized.isBlank() || normalized.equals("-") || normalized.equals(".")) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(normalized);
            return negative && decimal.signum() > 0 ? decimal.negate() : decimal;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal baseValue(FinancialFact fact) {
        return fact.value().multiply(scaleMultiplier(fact.scale()), MATH_CONTEXT);
    }

    private BigDecimal displayedValue(BigDecimal baseValue, String scale) {
        return baseValue.divide(scaleMultiplier(scale), MATH_CONTEXT);
    }

    private BigDecimal scaleMultiplier(String scale) {
        return switch (normalizeScale(scale)) {
            case "thousand" -> BigDecimal.valueOf(1_000L);
            case "million" -> BigDecimal.valueOf(1_000_000L);
            case "billion" -> BigDecimal.valueOf(1_000_000_000L);
            default -> BigDecimal.ONE;
        };
    }

    private String normalizeUnit(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "$", "usd", "dollar", "dollars", "us dollar", "us dollars", "u.s. dollars" -> "usd";
            case "%", "percentage" -> "percent";
            case "", "other" -> "other";
            default -> normalized;
        };
    }

    private String canonicalMetric(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").strip();
        if (containsAny(normalized, "net sales", "total sales", "sales revenue", "revenues", "revenue")) {
            return "revenue";
        }
        if (containsAny(normalized, "net earnings", "net profit", "net income")) {
            return "net income";
        }
        if (containsAny(normalized, "income from operations", "operating profit", "operating income")) {
            return "operating income";
        }
        if (containsAny(normalized, "total assets", "assets total")) {
            return "total assets";
        }
        if (containsAny(normalized, "income before income taxes", "pre tax income", "pretax income")) {
            return "income before tax";
        }
        if (containsAny(normalized, "income tax expense", "income taxes")) {
            return "income taxes";
        }
        return normalized;
    }

    private String normalizeScale(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "thousand", "thousands" -> "thousand";
            case "million", "millions" -> "million";
            case "billion", "billions" -> "billion";
            default -> "unit";
        };
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? Objects.toString(fallback, "") : value;
    }

    private String extractJsonObject(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private String normalize(String value) {
        return value == null ? "" : WHITESPACE.matcher(value.strip()).replaceAll(" ");
    }

    enum CalculationKind {
        NONE, DIFFERENCE, PERCENTAGE_CHANGE, RATIO, SUM
    }

    record EvidenceDocument(String evidenceId, String chunkId, String sourceFile, String company, String fiscalYear,
                            String modality, String item, String sectionTitle, String content,
                            List<String> matchedTaskIds) {
    }

    record FinancialFact(String factId, String evidenceId, String company, String fiscalYear, String metric,
                         String rawValue, BigDecimal value, String unit, String scale, String quote) {
    }

    record VerifiedCalculation(String calculationId, String type, String expression, BigDecimal result,
                               String unit, String scale, List<String> sourceFactIds) {
        String displayResult() {
            return result.setScale(Math.min(Math.max(result.scale(), 0), 4), RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
    }

    private record RowCandidate(EvidenceDocument document, String metric, String quote,
                                List<BigDecimal> values, String scale, BigDecimal distance) {
    }

    record Ledger(List<EvidenceDocument> evidence, List<FinancialFact> facts,
                  List<VerifiedCalculation> calculations) {
        String render() {
            StringBuilder builder = new StringBuilder("Evidence Ledger（唯一允许引用的证据）：\n");
            for (EvidenceDocument document : evidence) {
                builder.append('[').append(document.evidenceId()).append("] ")
                        .append("company=").append(document.company())
                        .append(" | fiscalYear=").append(document.fiscalYear())
                        .append(" | source=").append(document.sourceFile())
                        .append(" | modality=").append(document.modality());
                if (!document.item().isBlank()) {
                    builder.append(" | item=").append(document.item());
                }
                if (!document.sectionTitle().isBlank()) {
                    builder.append(" | section=").append(document.sectionTitle());
                }
                if (!document.matchedTaskIds().isEmpty()) {
                    builder.append(" | tasks=").append(document.matchedTaskIds());
                }
                builder.append('\n').append(document.content()).append("\n\n");
            }
            builder.append("已通过原文校验的结构化事实：\n");
            if (facts.isEmpty()) {
                builder.append("(无；不要从上下文中自行推导未列出的数值事实)\n");
            } else {
                for (FinancialFact fact : facts) {
                    builder.append('[').append(fact.factId()).append("] ")
                            .append(fact.company()).append(' ').append(fact.fiscalYear()).append(' ')
                            .append(fact.metric()).append(" = ").append(fact.rawValue())
                            .append(" | normalized=").append(fact.value().toPlainString()).append(' ')
                            .append(fact.scale()).append(' ').append(fact.unit())
                            .append(" | evidence=").append(fact.evidenceId()).append('\n');
                }
            }
            builder.append("确定性计算结果：\n");
            if (calculations.isEmpty()) {
                builder.append("(无)\n");
            } else {
                for (VerifiedCalculation calculation : calculations) {
                    builder.append('[').append(calculation.calculationId()).append("] ")
                            .append(calculation.type()).append(": ").append(calculation.expression())
                            .append(" = ").append(calculation.displayResult()).append(' ')
                            .append(calculation.scale()).append(' ').append(calculation.unit())
                            .append(" | facts=").append(calculation.sourceFactIds()).append('\n');
                }
            }
            return builder.toString().trim();
        }
    }
}
