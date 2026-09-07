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
import java.util.regex.Pattern;

final class FinancialEvidenceLedger {

    private static final int MAX_FACTS = 40;
    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ObjectMapper objectMapper;

    FinancialEvidenceLedger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Ledger build(String question, List<EvidenceDocument> documents, String extractionJson) {
        List<FinancialFact> facts = parseVerifiedFacts(documents, extractionJson);
        return new Ledger(List.copyOf(documents), facts, calculate(question, facts));
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
                - quote must be copied exactly from the selected evidence and must contain rawValue.
                - Include only facts whose company, fiscal year, metric and period can be determined from the evidence.
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
                BigDecimal value = parseDecimal(text(node, "value"));
                if (document == null || metric.isBlank() || quote.isBlank() || rawValue.isBlank() || value == null) {
                    continue;
                }
                String normalizedDocument = normalize(document.content());
                String normalizedQuote = normalize(quote);
                if (normalizedQuote.isBlank() || !normalizedDocument.contains(normalizedQuote)
                        || !normalize(quote).contains(normalize(rawValue))) {
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
        String outputScale = operands.stream().map(FinancialFact::scale)
                .max((left, right) -> scaleMultiplier(left).compareTo(scaleMultiplier(right))).orElse("unit");
        BigDecimal baseResult = operands.stream().map(this::baseValue)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MATH_CONTEXT));
        BigDecimal result = displayedValue(baseResult, outputScale);
        return List.of(new VerifiedCalculation("C1", "sum",
                String.join(" + ", operands.stream().map(FinancialFact::factId).toList()),
                result, operands.get(0).unit(), outputScale,
                operands.stream().map(FinancialFact::factId).toList()));
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
        if (containsAny(question, "combined", "sum of", "total of", "合计", "总和")) {
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
        return left.evidenceId().equals(right.evidenceId())
                && normalize(left.metric()).equalsIgnoreCase(normalize(right.metric()))
                && left.value().compareTo(right.value()) == 0
                && left.fiscalYear().equals(right.fiscalYear());
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
