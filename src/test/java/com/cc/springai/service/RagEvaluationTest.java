package com.cc.springai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.mcp.client.toolcallback.enabled=false"
})
@EnabledIfSystemProperty(named = "rag.evaluation.enabled", matches = "true")
class RagEvaluationTest {

    private static final String CONVERSATION_ID_PROPERTY = "rag.evaluation.conversation-id";
    private static final int TOP_K = 5;

    @Autowired
    private RagService ragService;

    @Test
    void evaluatesRagRetrievalAndWritesReport() throws IOException {
        String conversationId = System.getProperty(CONVERSATION_ID_PROPERTY);
        assertThat(conversationId)
                .as("Run with -D%s=<uploaded knowledge session id>", CONVERSATION_ID_PROPERTY)
                .isNotBlank();

        List<EvaluationCase> cases = readEvaluationCases();
        assertThat(cases).isNotEmpty();

        List<EvaluationResult> results = new ArrayList<>();
        for (EvaluationCase evaluationCase : cases) {
            List<RagService.RagChunk> chunks = ragService.search(evaluationCase.question(), conversationId, TOP_K);
            results.add(score(evaluationCase, chunks));
        }

        writeReport(results);

        long answerableCount = results.stream()
                .filter(result -> !result.evaluationCase().isNoAnswerCase())
                .count();
        long hitCount = results.stream()
                .filter(result -> !result.evaluationCase().isNoAnswerCase())
                .filter(result -> result.retrievalScore() > 0)
                .count();

        if (answerableCount > 0) {
            double hitRate = hitCount * 1.0 / answerableCount;
            assertThat(hitRate)
                    .as("Retrieval hit rate, see target/rag-evaluation-report.md")
                    .isGreaterThanOrEqualTo(0.70);
        }
    }

    private List<EvaluationCase> readEvaluationCases() {
        try {
            Path path = Path.of("src/test/resources/rag-evaluation-cases.csv");
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<EvaluationCase> cases = new ArrayList<>();
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> columns = parseCsvLine(line);
                if (columns.size() < 5) {
                    throw new IllegalArgumentException("Invalid evaluation case at line " + (index + 1));
                }
                cases.add(new EvaluationCase(
                        columns.get(0).trim(),
                        columns.get(1).trim(),
                        columns.get(2).trim(),
                        columns.get(3).trim(),
                        columns.get(4).trim()
                ));
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read RAG evaluation cases", e);
        }
    }

    private EvaluationResult score(EvaluationCase evaluationCase, List<RagService.RagChunk> chunks) {
        boolean noAnswerCase = evaluationCase.isNoAnswerCase();
        boolean hasEvidence = !evaluationCase.evidence().isBlank();
        boolean anyHit = hasEvidence && chunks.stream()
                .anyMatch(chunk -> containsEvidence(chunk.text(), evaluationCase.evidence()));
        boolean firstHit = hasEvidence && !chunks.isEmpty()
                && containsEvidence(chunks.get(0).text(), evaluationCase.evidence());

        int retrievalScore;
        if (noAnswerCase) {
            retrievalScore = chunks.isEmpty() ? 2 : 0;
        } else if (firstHit) {
            retrievalScore = 2;
        } else if (anyHit) {
            retrievalScore = 1;
        } else {
            retrievalScore = 0;
        }

        String firstChunkPreview = chunks.isEmpty() ? "" : preview(chunks.get(0).text());
        return new EvaluationResult(evaluationCase, chunks.size(), retrievalScore, firstHit, anyHit, firstChunkPreview);
    }

    private boolean containsEvidence(String text, String evidence) {
        return normalize(text).contains(normalize(evidence));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String preview(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "...";
    }

    private void writeReport(List<EvaluationResult> results) throws IOException {
        Path targetDir = Path.of("target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("rag-evaluation-report.md"), buildMarkdownReport(results), StandardCharsets.UTF_8);
        Files.writeString(targetDir.resolve("rag-evaluation-report.csv"), buildCsvReport(results), StandardCharsets.UTF_8);
    }

    private String buildMarkdownReport(List<EvaluationResult> results) {
        long answerableCount = results.stream()
                .filter(result -> !result.evaluationCase().isNoAnswerCase())
                .count();
        long hitCount = results.stream()
                .filter(result -> !result.evaluationCase().isNoAnswerCase())
                .filter(result -> result.retrievalScore() > 0)
                .count();
        long firstHitCount = results.stream()
                .filter(result -> !result.evaluationCase().isNoAnswerCase())
                .filter(EvaluationResult::firstHit)
                .count();
        long noAnswerCount = results.stream()
                .filter(result -> result.evaluationCase().isNoAnswerCase())
                .count();
        long noAnswerPassedCount = results.stream()
                .filter(result -> result.evaluationCase().isNoAnswerCase())
                .filter(result -> result.retrievalScore() == 2)
                .count();

        StringBuilder report = new StringBuilder();
        report.append("# RAG Evaluation Report\n\n");
        report.append("- Total cases: ").append(results.size()).append('\n');
        report.append("- Retrieval hit rate: ").append(formatRate(hitCount, answerableCount)).append('\n');
        report.append("- First hit rate: ").append(formatRate(firstHitCount, answerableCount)).append('\n');
        report.append("- No-answer pass rate: ").append(formatRate(noAnswerPassedCount, noAnswerCount)).append("\n\n");
        report.append("| id | type | retrieval | first hit | returned chunks | question | first chunk preview | answer score | faithfulness | completeness |\n");
        report.append("|---|---|---:|---|---:|---|---|---|---|---|\n");
        for (EvaluationResult result : results) {
            EvaluationCase evaluationCase = result.evaluationCase();
            report.append("| ")
                    .append(escapeMarkdown(evaluationCase.id()))
                    .append(" | ")
                    .append(escapeMarkdown(evaluationCase.type()))
                    .append(" | ")
                    .append(result.retrievalScore())
                    .append(" | ")
                    .append(result.firstHit() ? "yes" : "no")
                    .append(" | ")
                    .append(result.returnedChunks())
                    .append(" | ")
                    .append(escapeMarkdown(evaluationCase.question()))
                    .append(" | ")
                    .append(escapeMarkdown(result.firstChunkPreview()))
                    .append(" |  |  |  |\n");
        }
        report.append("\nManual answer scoring: 0 = wrong, 1 = partial, 2 = good.\n");
        return report.toString();
    }

    private String buildCsvReport(List<EvaluationResult> results) {
        StringBuilder report = new StringBuilder();
        report.append("id,type,question,retrieval_score,first_hit,any_hit,returned_chunks,answer_score,faithfulness,completeness,first_chunk_preview\n");
        for (EvaluationResult result : results) {
            EvaluationCase evaluationCase = result.evaluationCase();
            report.append(toCsv(evaluationCase.id())).append(',')
                    .append(toCsv(evaluationCase.type())).append(',')
                    .append(toCsv(evaluationCase.question())).append(',')
                    .append(result.retrievalScore()).append(',')
                    .append(result.firstHit()).append(',')
                    .append(result.anyHit()).append(',')
                    .append(result.returnedChunks()).append(",,,,")
                    .append(toCsv(result.firstChunkPreview()))
                    .append('\n');
        }
        return report.toString();
    }

    private String formatRate(long numerator, long denominator) {
        if (denominator == 0) {
            return "n/a";
        }
        return "%.1f%% (%d/%d)".formatted(numerator * 100.0 / denominator, numerator, denominator);
    }

    private String escapeMarkdown(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private String toCsv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean inQuotes = false;

        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (current == ',' && !inQuotes) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }

        values.add(value.toString());
        return values;
    }

    private record EvaluationCase(
            String id,
            String type,
            String question,
            String expectedAnswer,
            String evidence
    ) {
        private boolean isNoAnswerCase() {
            return "无答案题".equals(type) || "no-answer".equalsIgnoreCase(type) || evidence.isBlank();
        }
    }

    private record EvaluationResult(
            EvaluationCase evaluationCase,
            int returnedChunks,
            int retrievalScore,
            boolean firstHit,
            boolean anyHit,
            String firstChunkPreview
    ) {
    }
}
