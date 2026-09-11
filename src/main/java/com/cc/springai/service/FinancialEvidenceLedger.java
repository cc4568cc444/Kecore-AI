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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FinancialEvidenceLedger {

    private static final int MAX_FACTS = 40;
    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NUMERIC_VALUE = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.[ \t]*\\d+)?");
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\|\\s*([^|]+?)\\s*\\|([^\\r\\n]+)$");
    private static final Pattern ROW_NUMBER = Pattern.compile("\\(?\\d[\\d,]*(?:\\.\\d+)?\\)?");
    private static final Pattern NET_INCOME_PREMISE = Pattern.compile(
            "(?i)net income for fiscal(?: year)?\\s*(20\\d{2})\\s+is\\s+\\$?([\\d,.]+)\\s+(million|billion)");
    private static final Pattern COMBINED_CASH_TEXT = Pattern.compile(
            "(?is)(cash\\s*,?\\s*cash equivalents\\s*,?\\s*(?:and\\s+)?short-term investments)"
                    + "(?:\\s+\\w+){0,10}\\s+\\$?([\\d,.]+)\\s+(million|billion)");

    private final ObjectMapper objectMapper;

    FinancialEvidenceLedger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Ledger build(String question, List<EvidenceDocument> documents, String extractionJson) {
        return build(question, documents, extractionJson, List.of());
    }

    Ledger build(String question, List<EvidenceDocument> documents, String extractionJson,
                 List<CalculationPlan> calculationPlans) {
        List<FinancialFact> facts = filterPremiseConsistentFacts(question, mergeFacts(
                groundedPlanFacts(calculationPlans, documents), mergeFacts(
                        deterministicFacts(question, documents), parseVerifiedFacts(documents, extractionJson))));
        List<EvidenceDocument> evidence = List.copyOf(documents);
        return new Ledger(evidence, facts, calculateAll(calculationPlans, facts, evidence));
    }

    Ledger augment(String question, Ledger existing, String extractionJson) {
        return augment(question, existing, extractionJson, List.of());
    }

    Ledger augment(String question, Ledger existing, String extractionJson,
                   List<CalculationPlan> calculationPlans) {
        List<FinancialFact> merged = mergeFacts(
                groundedPlanFacts(calculationPlans, existing.evidence()),
                mergeFacts(deterministicFacts(question, existing.evidence()),
                        mergeFacts(existing.facts(), parseVerifiedFacts(existing.evidence(), extractionJson))));
        List<FinancialFact> renumbered = new ArrayList<>();
        for (FinancialFact fact : merged) {
            renumbered.add(new FinancialFact(
                    "F" + (renumbered.size() + 1), fact.evidenceId(), fact.company(), fact.fiscalYear(), fact.metric(),
                    fact.rawValue(), fact.value(), fact.unit(), fact.scale(), fact.quote()));
        }
        List<FinancialFact> facts = filterPremiseConsistentFacts(question, List.copyOf(renumbered));
        return new Ledger(existing.evidence(), facts,
                calculateAll(calculationPlans, facts, existing.evidence()));
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
                addCombinedCashTextFact(facts, document);
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
            if (best != null && best.distance().compareTo(statedBase.multiply(new BigDecimal("0.08"))) <= 0) {
                facts.add(tableFact(best.document(), best.metric(), currentYear, best.values().get(0), best.scale(), best.quote()));
                facts.add(tableFact(best.document(), best.metric(), Integer.toString(Integer.parseInt(currentYear) - 1),
                        best.values().get(1), best.scale(), best.quote()));
            }
        }
        return mergeFacts(List.of(), facts);
    }

    private List<FinancialFact> filterPremiseConsistentFacts(String question, List<FinancialFact> facts) {
        Matcher premise = NET_INCOME_PREMISE.matcher(question == null ? "" : question);
        if (!premise.find()) {
            return facts;
        }
        String currentYear = premise.group(1);
        BigDecimal stated = parseDecimal(premise.group(2));
        if (stated == null) {
            return facts;
        }
        BigDecimal statedBase = stated.multiply(scaleMultiplier(premise.group(3)), MATH_CONTEXT);
        FinancialFact best = facts.stream()
                .filter(fact -> currentYear.equals(fact.fiscalYear()))
                .filter(fact -> "net income".equals(canonicalMetric(fact.metric())))
                .min((left, right) -> baseValue(left).subtract(statedBase).abs()
                        .compareTo(baseValue(right).subtract(statedBase).abs()))
                .orElse(null);
        if (best == null || baseValue(best).subtract(statedBase).abs()
                .compareTo(statedBase.multiply(new BigDecimal("0.08"))) > 0) {
            return facts.stream().filter(fact -> !"net income".equals(canonicalMetric(fact.metric()))).toList();
        }
        String selectedMetric = normalize(best.metric()).toLowerCase(Locale.ROOT);
        return facts.stream().filter(fact -> !"net income".equals(canonicalMetric(fact.metric()))
                || normalize(fact.metric()).toLowerCase(Locale.ROOT).equals(selectedMetric)).toList();
    }

    private void addCombinedCashTextFact(List<FinancialFact> facts, EvidenceDocument document) {
        Matcher matcher = COMBINED_CASH_TEXT.matcher(document.content());
        if (!matcher.find()) {
            return;
        }
        BigDecimal value = parseDecimal(matcher.group(2));
        if (value == null) {
            return;
        }
        String scale = normalizeScale(matcher.group(3));
        String quote = normalize(matcher.group());
        facts.add(new FinancialFact("", document.evidenceId(), document.company(), document.fiscalYear(),
                "Total cash, cash equivalents, and short-term investments",
                "$" + value.toPlainString() + " " + scale, value, "usd", scale, quote));
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
                - For a requested company total, emit the directly reported consolidated/total value when present;
                  never substitute a regional, segment, product, or streaming-only row. If multiple scoped rows exist
                  and no aggregate row is supplied, return no fact for that operand.
                - Otherwise, for a requested total emit every named component needed to calculate it. Never emit only
                  one component of a requested total.
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
                        || !rawValueSupportedByQuote(rawValue, quote)
                        || !metricSupportedByQuote(metric, rawValue, quote)) {
                    continue;
                }
                String company = defaultIfBlank(text(node, "company"), document.company());
                if (!document.company().isBlank() && !company.equalsIgnoreCase(document.company())) {
                    continue;
                }
                String fiscalYear = defaultIfBlank(text(node, "fiscalYear"), document.fiscalYear());
                FinancialFact candidate = new FinancialFact(
                        "F" + (facts.size() + 1), evidenceId, company, fiscalYear, metric,
                        rawValue, value, normalizeUnit(text(node, "unit")), verifiedScale(rawValue, text(node, "scale")), quote);
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

    private List<FinancialFact> groundedPlanFacts(List<CalculationPlan> plans,
                                                  List<EvidenceDocument> documents) {
        if (plans == null || plans.isEmpty() || documents.isEmpty()) {
            return List.of();
        }
        List<FinancialFact> facts = new ArrayList<>();
        for (CalculationPlan plan : plans) {
            if (plan.operator() == CalculationOperator.COUNT) {
                continue;
            }
            for (CalculationOperand operand : plan.operands()) {
                BigDecimal value = parseRawDecimal(operand.rawValue());
                if (value == null || operand.metric().isBlank()) {
                    continue;
                }
                List<EvidenceDocument> candidates = new ArrayList<>();
                documents.stream()
                        .filter(document -> operand.sourceTaskId().isBlank()
                                || document.matchedTaskIds().contains(operand.sourceTaskId()))
                        .forEach(candidates::add);
                documents.stream()
                        .filter(document -> !candidates.contains(document))
                        .forEach(candidates::add);
                for (EvidenceDocument document : candidates) {
                    if (!operand.company().isBlank()
                            && !operand.company().equalsIgnoreCase(document.company())) {
                        continue;
                    }
                    boolean exactTaskMatch = operand.sourceTaskId().isBlank()
                            || document.matchedTaskIds().contains(operand.sourceTaskId());
                    // A single narrative can ground several planner operands even when retrieval attached it
                    // only to the broad anchor task. The fallback remains strict: same company, same filing
                    // year, exact number and a quote that explicitly supports the requested metric.
                    if (!exactTaskMatch && (operand.company().isBlank() || operand.fiscalYear().isBlank()
                            || !operand.fiscalYear().equals(document.fiscalYear()))) {
                        continue;
                    }
                    String quote = numericEvidenceQuote(document.content(), operand.rawValue());
                    if (quote.isBlank() || !metricSupportedByQuote(operand.metric(), operand.rawValue(), quote)) {
                        continue;
                    }
                    facts.add(new FinancialFact("", document.evidenceId(),
                            defaultIfBlank(operand.company(), document.company()),
                            defaultIfBlank(operand.fiscalYear(), document.fiscalYear()), operand.metric(),
                            operand.rawValue(), value, normalizeUnit(operand.unit()),
                            verifiedScale(operand.rawValue(), operand.scale()), quote));
                    break;
                }
            }
        }
        return mergeFacts(List.of(), facts);
    }

    private String numericEvidenceQuote(String content, String rawValue) {
        BigDecimal requested = parseRawDecimal(rawValue);
        if (requested == null || content == null || content.isBlank()) {
            return "";
        }
        Matcher matcher = NUMERIC_VALUE.matcher(content);
        while (matcher.find()) {
            BigDecimal candidate = parseDecimal(matcher.group());
            if (candidate == null || candidate.compareTo(requested) != 0) {
                continue;
            }
            int start = Math.max(0, matcher.start() - 220);
            int end = Math.min(content.length(), matcher.end() + 220);
            int lineStart = content.lastIndexOf('\n', matcher.start());
            int lineEnd = content.indexOf('\n', matcher.end());
            if (lineStart >= 0 && matcher.start() - lineStart <= 500) {
                start = lineStart + 1;
            }
            if (lineEnd >= 0 && lineEnd - matcher.end() <= 500) {
                end = lineEnd;
            }
            return content.substring(start, end).strip();
        }
        return "";
    }

    private List<VerifiedCalculation> calculateAll(List<CalculationPlan> plans, List<FinancialFact> facts,
                                                   List<EvidenceDocument> documents) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        List<VerifiedCalculation> calculations = new ArrayList<>();
        for (CalculationPlan plan : plans) {
            executePlan(plan, facts, documents, calculations.size() + 1).ifPresent(calculations::add);
        }
        return List.copyOf(calculations);
    }

    List<VerifiedCalculation> calculate(List<CalculationPlan> plans, List<FinancialFact> facts,
                                        List<EvidenceDocument> documents) {
        return calculateAll(plans, facts, documents);
    }

    private java.util.Optional<VerifiedCalculation> executePlan(
            CalculationPlan plan, List<FinancialFact> facts, List<EvidenceDocument> documents, int number) {
        if (plan == null || plan.operator() == CalculationOperator.NONE) {
            return java.util.Optional.empty();
        }
        if (plan.operator() == CalculationOperator.COUNT) {
            List<String> countedValues = plan.operands().stream()
                    .map(operand -> defaultIfBlank(operand.rawValue(), operand.fiscalYear()))
                    .map(this::normalize).filter(value -> !value.isBlank()).distinct().toList();
            boolean supported = countedValues.size() == plan.operands().size()
                    && plan.operands().stream().allMatch(operand -> countOperandSupported(operand, documents));
            if (!supported) {
                return java.util.Optional.empty();
            }
            BigDecimal result = BigDecimal.valueOf(countedValues.size());
            return java.util.Optional.of(new VerifiedCalculation(
                    "C" + number, "count", "COUNT(" + String.join(", ", countedValues) + ")",
                    result, "count", "unit", List.of()));
        }
        List<FinancialFact> operands = bindOperands(plan.operands(), facts, documents);
        if (operands.size() != plan.operands().size() || operands.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<BigDecimal> values = operands.stream().map(this::baseValue).toList();
        BigDecimal result = null;
        String defaultUnit = operands.get(0).unit();
        String defaultScale = operands.get(0).scale();
        switch (plan.operator()) {
            case ADD, SUM -> result = values.stream().reduce(BigDecimal.ZERO,
                    (left, right) -> left.add(right, MATH_CONTEXT));
            case SUBTRACT -> {
                if (values.size() != 2) return java.util.Optional.empty();
                result = values.get(0).subtract(values.get(1), MATH_CONTEXT);
            }
            case DIVIDE -> {
                if (values.size() != 2 || values.get(1).compareTo(BigDecimal.ZERO) == 0) {
                    return java.util.Optional.empty();
                }
                result = values.get(0).divide(values.get(1), MATH_CONTEXT);
                defaultUnit = "times";
                defaultScale = "unit";
            }
            case PERCENT_CHANGE -> {
                if (values.size() != 2 || values.get(0).compareTo(BigDecimal.ZERO) == 0) {
                    return java.util.Optional.empty();
                }
                result = values.get(1).subtract(values.get(0), MATH_CONTEXT)
                        .divide(values.get(0), MATH_CONTEXT).multiply(BigDecimal.valueOf(100));
                defaultUnit = "percent";
                defaultScale = "unit";
            }
            case PERCENT_OF_TOTAL -> {
                if (values.size() != 2 || values.get(1).compareTo(BigDecimal.ZERO) == 0) {
                    return java.util.Optional.empty();
                }
                result = values.get(0).divide(values.get(1), MATH_CONTEXT).multiply(BigDecimal.valueOf(100));
                defaultUnit = "percent";
                defaultScale = "unit";
            }
            case AVERAGE -> result = values.stream().reduce(BigDecimal.ZERO,
                    (left, right) -> left.add(right, MATH_CONTEXT))
                    .divide(BigDecimal.valueOf(values.size()), MATH_CONTEXT);
            case CAGR -> {
                if (values.size() != 2 || values.get(0).compareTo(BigDecimal.ZERO) <= 0
                        || values.get(1).compareTo(BigDecimal.ZERO) < 0 || plan.periods() <= 0) {
                    return java.util.Optional.empty();
                }
                double cagr = Math.pow(values.get(1).divide(values.get(0), MATH_CONTEXT).doubleValue(),
                        1.0d / plan.periods()) - 1.0d;
                result = BigDecimal.valueOf(cagr).multiply(BigDecimal.valueOf(100));
                defaultUnit = "percent";
                defaultScale = "unit";
            }
            case COUNT -> {
                result = BigDecimal.valueOf(operands.size());
                defaultUnit = "count";
                defaultScale = "unit";
            }
            case NONE -> {
                return java.util.Optional.empty();
            }
        }
        if (result == null) {
            return java.util.Optional.empty();
        }
        String outputUnit = defaultIfBlank(plan.outputUnit(), defaultUnit);
        String outputScale = defaultIfBlank(plan.outputScale(), defaultScale);
        if (!"unit".equals(outputScale) && !Set.of("percent", "times", "count").contains(outputUnit)) {
            result = displayedValue(result, outputScale);
        }
        int precision = Math.max(0, Math.min(8, plan.precision()));
        result = result.setScale(precision, RoundingMode.HALF_UP).stripTrailingZeros();
        List<String> factIds = operands.stream().map(FinancialFact::factId).toList();
        return java.util.Optional.of(new VerifiedCalculation(
                "C" + number, calculationType(plan.operator(), outputUnit),
                expression(plan.operator(), factIds), result, outputUnit, outputScale, factIds));
    }

    private String calculationType(CalculationOperator operator, String outputUnit) {
        return switch (operator) {
            case SUBTRACT -> "difference";
            case PERCENT_CHANGE -> "percentage_change";
            case PERCENT_OF_TOTAL -> "ratio";
            case DIVIDE -> "times".equalsIgnoreCase(outputUnit) ? "multiple" : "ratio";
            default -> operator.name().toLowerCase(Locale.ROOT);
        };
    }

    private List<FinancialFact> bindOperands(List<CalculationOperand> selectors, List<FinancialFact> facts,
                                             List<EvidenceDocument> documents) {
        List<FinancialFact> bound = new ArrayList<>();
        for (CalculationOperand selector : selectors) {
            List<FinancialFact> candidates = facts.stream()
                    .filter(fact -> selector.company().isBlank()
                            || selector.company().equalsIgnoreCase(fact.company()))
                    .filter(fact -> selector.fiscalYear().isBlank()
                            || selector.fiscalYear().equals(fact.fiscalYear()))
                    .filter(fact -> selector.metric().isBlank()
                            || metricMatches(selector.metric(), fact.metric()))
                    .filter(fact -> sourceTaskMatches(selector, fact, documents))
                    .toList();
            FinancialFact match = selectUnambiguousOperand(candidates);
            if (match == null || bound.stream().anyMatch(existing -> existing.factId().equals(match.factId()))) {
                return List.of();
            }
            bound.add(match);
        }
        return List.copyOf(bound);
    }

    private boolean countOperandSupported(CalculationOperand operand, List<EvidenceDocument> documents) {
        String countedValue = defaultIfBlank(operand.rawValue(), operand.fiscalYear());
        if (countedValue.isBlank()) {
            return false;
        }
        return documents.stream()
                .filter(document -> operand.company().isBlank()
                        || operand.company().equalsIgnoreCase(document.company()))
                .filter(document -> operand.sourceTaskId().isBlank()
                        || document.matchedTaskIds().contains(operand.sourceTaskId()))
                .anyMatch(document -> rawValueSupportedByQuote(countedValue, document.content()));
    }

    private boolean sourceTaskMatches(CalculationOperand selector, FinancialFact fact,
                                      List<EvidenceDocument> documents) {
        if (selector.sourceTaskId().isBlank()) {
            return true;
        }
        EvidenceDocument evidence = documents.stream()
                .filter(document -> document.evidenceId().equals(fact.evidenceId()))
                .findFirst().orElse(null);
        if (evidence == null) {
            return false;
        }
        if (evidence.matchedTaskIds().contains(selector.sourceTaskId())) {
            return true;
        }
        // A later filing's multi-year table can contain an audited value for an earlier fiscal year.
        // The chunk is tagged to the filing-year task, while the fact is correctly tagged to its column year.
        return !selector.company().isBlank() && !selector.fiscalYear().isBlank() && !selector.metric().isBlank()
                && selector.company().equalsIgnoreCase(fact.company())
                && selector.fiscalYear().equals(fact.fiscalYear())
                && metricMatches(selector.metric(), fact.metric());
    }

    private FinancialFact selectUnambiguousOperand(List<FinancialFact> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        List<FinancialFact> distinct = new ArrayList<>();
        for (FinancialFact candidate : candidates) {
            if (distinct.stream().noneMatch(existing -> existing.value().compareTo(candidate.value()) == 0
                    && normalizeScale(existing.scale()).equals(normalizeScale(candidate.scale())))) {
                distinct.add(candidate);
            }
        }
        if (distinct.size() == 1) {
            return distinct.get(0);
        }
        List<FinancialFact> aggregate = distinct.stream().filter(this::isAggregateFact).toList();
        // Never choose an arbitrary regional or segment row for a company-level calculation.
        return aggregate.size() == 1 ? aggregate.get(0) : null;
    }

    private boolean isAggregateFact(FinancialFact fact) {
        String scope = (normalize(fact.metric()) + " " + normalize(fact.quote())).toLowerCase(Locale.ROOT);
        return containsAny(scope, "total ", "consolidated", "worldwide", "global", "all regions");
    }

    private String expression(CalculationOperator operator, List<String> factIds) {
        String separator = switch (operator) {
            case ADD, SUM -> " + ";
            case SUBTRACT -> " - ";
            case DIVIDE, PERCENT_OF_TOTAL -> " / ";
            case PERCENT_CHANGE -> " -> ";
            case AVERAGE -> ", ";
            case CAGR -> " -> ";
            case COUNT -> ", ";
            case NONE -> " ";
        };
        return operator.name() + "(" + String.join(separator, factIds) + ")";
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

    /**
     * Prevents a model from assigning a nearby table value to a different metric. A quote may contain
     * a "Net sales" header while the selected numeric row is actually income before tax.
     */
    private boolean metricSupportedByQuote(String metric, String rawValue, String quote) {
        BigDecimal selectedValue = parseRawDecimal(rawValue);
        if (selectedValue == null) {
            return false;
        }
        Matcher rows = TABLE_ROW.matcher(quote);
        boolean foundSelectedValueRow = false;
        while (rows.find()) {
            boolean containsSelectedValue = rowValues(rows.group(2)).stream()
                    .anyMatch(value -> value.compareTo(selectedValue) == 0);
            if (!containsSelectedValue) {
                continue;
            }
            foundSelectedValueRow = true;
            if (metricMatches(metric, rows.group(1))) {
                return true;
            }
        }
        if (foundSelectedValueRow) {
            return false;
        }
        return narrativeContainsMetric(metric, quote);
    }

    boolean containsDirectMetricValue(String metric, String content) {
        if (metric == null || metric.isBlank() || content == null || content.isBlank()) {
            return false;
        }
        Matcher rows = TABLE_ROW.matcher(content);
        boolean hasTableRows = false;
        while (rows.find()) {
            hasTableRows = true;
            if (metricMatches(metric, rows.group(1))
                    && !rowValues(rows.group(2)).isEmpty()) {
                return true;
            }
        }
        if (hasTableRows) {
            return false;
        }
        return narrativeContainsMetric(metric, content) && NUMERIC_VALUE.matcher(content).find();
    }

    private boolean narrativeContainsMetric(String metric, String quote) {
        String canonical = canonicalMetric(metric);
        String lower = normalize(quote).toLowerCase(Locale.ROOT);
        return switch (canonical) {
            case "revenue", "total revenue" -> containsAny(lower,
                    "revenue", "revenues", "net sales", "total sales");
            case "net income" -> containsAny(lower, "net income", "net earnings", "net profit");
            case "net income change" -> containsAny(lower, "net income", "net earnings", "net profit")
                    && containsAny(lower, "increase", "decrease", "change", "difference");
            case "operating income" -> containsAny(lower, "operating income", "income from operations",
                    "operating profit");
            case "income before tax" -> containsAny(lower, "income before income taxes", "income before provision",
                    "pre-tax income", "pretax income");
            case "age" -> containsAny(lower, " age ", " aged ", " years old")
                    || Pattern.compile("(?i)\\b[A-Z][A-Za-z.'-]+(?:\\s+[A-Z][A-Za-z.'-]+){1,4}\\s*,\\s*"
                    + "(?:1[89]|[2-9]\\d)\\b").matcher(quote).find();
            default -> !canonical.isBlank() && lower.contains(canonical);
        };
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
        String withoutFootnote = defaultIfBlank(value, "").replaceFirst("\\s*\\(\\d+\\)\\s*$", "");
        String normalized = normalize(withoutFootnote).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").strip();
        if (normalized.contains("cash") && normalized.contains("cash equivalents")
                && normalized.contains("short term investments")) {
            return "total cash cash equivalents short term investments";
        }
        if (containsAny(normalized, "net sales", "total sales", "sales revenue", "revenues", "revenue")) {
            if (normalized.contains("streaming")) {
                return "streaming revenue";
            }
            if (normalized.contains("total") || normalized.contains("consolidated")) {
                return "total revenue";
            }
            return "revenue";
        }
        if (containsAny(normalized, "net earnings", "net profit", "net income")
                && containsAny(normalized, "increase", "decrease", "change", "difference")) {
            return "net income change";
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
        if (containsAny(normalized, "executive age", "officer age", "director age", "age")) {
            return "age";
        }
        return normalized;
    }

    private boolean metricMatches(String requested, String actual) {
        String requestedMetric = canonicalMetric(requested);
        String actualMetric = canonicalMetric(actual);
        if (requestedMetric.equals(actualMetric)) {
            return true;
        }
        // Consolidated tables often label their aggregate row simply "Revenues". Accept that generic
        // row for a total request, but never accept an explicitly scoped row such as Streaming revenues.
        return (requestedMetric.equals("total revenue") && actualMetric.equals("revenue"))
                || (requestedMetric.equals("revenue") && actualMetric.equals("total revenue"));
    }

    private String verifiedScale(String rawValue, String proposedScale) {
        // Displayed magnitude is authoritative; models sometimes copy the planner's assumed scale.
        Matcher explicit = Pattern.compile("(?i)\\b(thousand|million|billion)s?\\b")
                .matcher(defaultIfBlank(rawValue, ""));
        return explicit.find() ? normalizeScale(explicit.group(1)) : normalizeScale(proposedScale);
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

    enum CalculationOperator {
        NONE, ADD, SUBTRACT, DIVIDE, PERCENT_CHANGE, PERCENT_OF_TOTAL, SUM, COUNT, AVERAGE, CAGR;

        static CalculationOperator from(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return NONE;
            }
        }
    }

    record CalculationOperand(String role, String sourceTaskId, String company, String fiscalYear, String metric,
                              String rawValue, String unit, String scale) {
        CalculationOperand(String role, String sourceTaskId, String company, String fiscalYear, String metric) {
            this(role, sourceTaskId, company, fiscalYear, metric, "", "", "");
        }

        CalculationOperand {
            role = Objects.toString(role, "").strip();
            sourceTaskId = Objects.toString(sourceTaskId, "").strip();
            company = Objects.toString(company, "").strip();
            fiscalYear = Objects.toString(fiscalYear, "").strip();
            metric = Objects.toString(metric, "").strip();
            rawValue = Objects.toString(rawValue, "").strip();
            unit = Objects.toString(unit, "").strip();
            scale = Objects.toString(scale, "").strip();
        }
    }

    record CalculationPlan(String id, CalculationOperator operator, List<CalculationOperand> operands,
                           String outputUnit, String outputScale, int precision, int periods) {
        CalculationPlan {
            id = Objects.toString(id, "").strip();
            operator = operator == null ? CalculationOperator.NONE : operator;
            operands = operands == null ? List.of() : List.copyOf(operands);
            outputUnit = Objects.toString(outputUnit, "").strip().toLowerCase(Locale.ROOT);
            outputScale = Objects.toString(outputScale, "").strip().toLowerCase(Locale.ROOT);
            precision = Math.max(0, Math.min(8, precision));
            periods = Math.max(0, periods);
        }
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
            StringBuilder builder = new StringBuilder("Verified Evidence Slots (the only citable evidence):\n");
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
