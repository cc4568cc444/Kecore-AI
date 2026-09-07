package com.cc.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cc.springai.agent.ModelRuntimeOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FinancialRagService {

    private static final Logger log = LoggerFactory.getLogger(FinancialRagService.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9&.-]*|\\d+(?:,\\d{3})*(?:\\.\\d+)?%?");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b20(?:22|23|24)\\b");
    private static final int RETRIEVAL_QUERY_COUNT = 5;
    private static final String RETRIEVAL_STRATEGY_DEFAULT = "default";
    private static final String RETRIEVAL_STRATEGY_PARENT_CHILD = "parent-child";
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it",
            "of", "on", "or", "that", "the", "to", "was", "were", "what", "which", "with",
            "year", "years", "company", "fiscal", "financial", "annual", "report", "多少", "什么"
    );
    private static final Map<String, String> COMPANY_ALIASES = Map.ofEntries(
            Map.entry("apple", "AAPL"),
            Map.entry("microsoft", "MSFT"),
            Map.entry("nvidia", "NVDA"),
            Map.entry("netflix", "NFLX"),
            Map.entry("meta", "META"),
            Map.entry("facebook", "META"),
            Map.entry("amd", "AMD"),
            Map.entry("eli lilly", "LLY"),
            Map.entry("lilly", "LLY"),
            Map.entry("merck", "MRK"),
            Map.entry("pfizer", "PFE"),
            Map.entry("starbucks", "SBUX"),
            Map.entry("american express", "AXP"),
            Map.entry("linde", "LIN"),
            Map.entry("prologis", "PLD"),
            Map.entry("welltower", "WELL"),
            Map.entry("exelon", "EXC"),
            Map.entry("valero", "VLO"),
            Map.entry("amazon", "AMZN"),
            Map.entry("google", "GOOGL"),
            Map.entry("alphabet", "GOOGL"),
            Map.entry("jpmorgan", "JPM"),
            Map.entry("berkshire", "BRK-B"),
            Map.entry("tesla", "TSLA"),
            Map.entry("coca-cola", "KO"),
            Map.entry("coca cola", "KO"),
            Map.entry("walmart", "WMT")
    );

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChatClient financeChatClient;
    private final ChatMemory chatMemory;
    private final ModelConfigService modelConfigService;
    private final FinancialEvidenceLedger evidenceLedger;
    private final FinancialCitationVerifier citationVerifier;

    @Value("${app.financial-rag.table:multidoc_full_chunks}")
    private String tableName;

    @Value("${app.financial-rag.fallback-table:multidoc_s2_medium_chunks}")
    private String fallbackTableName;

    @Value("${app.financial-rag.legacy-fallback-table:multidoc_s2_medium_chunks}")
    private String legacyFallbackTableName;

    @Value("${app.financial-rag.top-k:8}")
    private int topK;

    @Value("${app.financial-rag.hybrid-top-k:15}")
    private int hybridTopK;

    @Value("${app.financial-rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${app.financial-rag.rerank.url:http://127.0.0.1:8010}")
    private String rerankUrl;

    @Value("${app.financial-rag.rerank.model:Qwen3-Reranker-0.6B}")
    private String rerankModel;

    @Value("${app.financial-rag.rerank.doc-chars:3000}")
    private int rerankDocChars;

    @Value("${app.financial-rag.rerank.candidate-limit:24}")
    private int rerankCandidateLimit;

    @Value("${app.financial-rag.retrieval-strategy:default}")
    private String retrievalStrategy;

    @Value("${app.financial-rag.evidence-ledger.enabled:true}")
    private boolean evidenceLedgerEnabled;

    @Value("${app.financial-rag.evidence-ledger.max-documents:15}")
    private int evidenceLedgerMaxDocuments;

    @Value("${app.financial-rag.evidence-ledger.max-content-chars:6000}")
    private int evidenceLedgerMaxContentChars;

    @Value("${app.financial-rag.citation-verifier.enabled:true}")
    private boolean citationVerifierEnabled;

    @Value("${app.financial-rag.citation-verifier.strict:true}")
    private boolean citationVerifierStrict;

    public FinancialRagService(EmbeddingModel embeddingModel,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               @Qualifier("financeChatClient") ChatClient financeChatClient,
                               ChatMemory chatMemory,
                               ModelConfigService modelConfigService) {
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.financeChatClient = financeChatClient;
        this.chatMemory = chatMemory;
        this.modelConfigService = modelConfigService;
        this.evidenceLedger = new FinancialEvidenceLedger(objectMapper);
        this.citationVerifier = new FinancialCitationVerifier();
    }

    public Flux<String> chat(String prompt, String conversationId, String modelId) {
        return chat(prompt, conversationId, modelId, FinancialRetrievalMode.fromConfig(retrievalStrategy));
    }

    public Flux<String> chat(String prompt, String conversationId, String modelId, FinancialRetrievalMode retrievalMode) {
        if (prompt == null || prompt.isBlank()) {
            return Flux.just("请输入要查询的金融年报问题。");
        }

        try {
            FinancialRetrievalPlan plan = planRetrieval(prompt, conversationId, modelId);
            List<FinancialChunk> chunks = search(plan, retrievalMode);
            if (chunks.isEmpty()) {
                return Flux.just("未在 Multi-Doc-2025 年报知识库中检索到相关片段。请确认索引已构建，或在问题中补充公司和年份。");
            }

            FinancialEvidenceLedger.Ledger ledger = buildEvidenceLedger(prompt, plan, chunks, retrievalMode, modelId);
            String userPrompt = answerPrompt(prompt, plan, ledger);

            Flux<String> answer = financeChatClient(modelId).prompt()
                    .user(userPrompt)
                    .options(modelConfigService.chatOptions(modelId))
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content();
            return verifyAnswer(answer, ledger);
        } catch (Exception ex) {
            return Flux.just("金融问答检索失败：" + ex.getMessage());
        }
    }

    public FinancialAnswerStream prepareChat(String prompt, String conversationId, String modelId) {
        return prepareChat(prompt, conversationId, modelId, null, FinancialRetrievalMode.fromConfig(retrievalStrategy));
    }

    public FinancialAnswerStream prepareChat(String prompt, String conversationId, String modelId,
                                             ModelRuntimeOptions runtimeOptions) {
        return prepareChat(prompt, conversationId, modelId, runtimeOptions, FinancialRetrievalMode.fromConfig(retrievalStrategy));
    }

    public FinancialAnswerStream prepareChat(String prompt, String conversationId, String modelId,
                                             ModelRuntimeOptions runtimeOptions, FinancialRetrievalMode retrievalMode) {
        long translationStartedAt = System.currentTimeMillis();
        if (prompt == null || prompt.isBlank()) {
            return new FinancialAnswerStream(Flux.just(new ModelStreamEvent("token", "请输入要查询的金融年报问题。")),
                    0L, 0L, false, "");
        }

        try {
            FinancialRetrievalPlan plan = planRetrieval(prompt, conversationId, modelId);
            String retrievalQuery = plan.displayQuery();
            long translationMs = elapsedMs(translationStartedAt);
            long retrievalStartedAt = System.currentTimeMillis();
            List<FinancialChunk> chunks = search(plan, retrievalMode);
            if (chunks.isEmpty()) {
                long retrievalMs = elapsedMs(retrievalStartedAt);
                return new FinancialAnswerStream(Flux.just(new ModelStreamEvent("token", "未在 Multi-Doc-2025 年报知识库中检索到相关片段。请确认索引已构建，或在问题中补充公司和年份。")),
                        translationMs, retrievalMs, false, retrievalQuery);
            }

            FinancialEvidenceLedger.Ledger ledger = buildEvidenceLedger(prompt, plan, chunks, retrievalMode, modelId);
            long retrievalMs = elapsedMs(retrievalStartedAt);
            String userPrompt = answerPrompt(prompt, plan, ledger);

            Flux<ModelStreamEvent> content = financeChatClient(modelId).prompt()
                    .user(userPrompt)
                    .options(modelConfigService.chatOptions(modelId, runtimeOptions))
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .chatResponse()
                    .flatMapIterable(this::eventsFromResponse);
            content = verifyAnswerEvents(content, ledger);
            return new FinancialAnswerStream(content, translationMs, retrievalMs, true, retrievalQuery);
        } catch (Exception ex) {
            long translationMs = elapsedMs(translationStartedAt);
            return new FinancialAnswerStream(Flux.just(new ModelStreamEvent("token", "金融问答检索失败：" + ex.getMessage())),
                    translationMs, 0L, false, "");
        }
    }

    public FinancialAnalysisResult analyze(String prompt, String conversationId, String modelId,
                                           FinancialRetrievalMode retrievalMode) {
        if (prompt == null || prompt.isBlank()) {
            return FinancialAnalysisResult.failed("请输入要查询的金融年报问题。");
        }
        long startedAt = System.currentTimeMillis();
        try {
            long planningStartedAt = System.currentTimeMillis();
            FinancialRetrievalPlan plan = planRetrieval(prompt, conversationId, modelId);
            long planningMs = elapsedMs(planningStartedAt);

            long retrievalStartedAt = System.currentTimeMillis();
            List<FinancialChunk> chunks = search(plan, retrievalMode);
            if (chunks.isEmpty()) {
                return new FinancialAnalysisResult("", plan.resolvedQuestion(), plan.intent(),
                        analysisTasks(plan), List.of(), List.of(), List.of(),
                        new CitationAudit(false, List.of("没有检索到证据")), planningMs,
                        elapsedMs(retrievalStartedAt), 0L, elapsedMs(startedAt), "没有检索到证据");
            }
            FinancialEvidenceLedger.Ledger ledger = buildEvidenceLedger(prompt, plan, chunks, retrievalMode, modelId);
            long retrievalMs = elapsedMs(retrievalStartedAt);

            long generationStartedAt = System.currentTimeMillis();
            String generated = financeChatClient(modelId).prompt()
                    .user(answerPrompt(prompt, plan, ledger))
                    .options(modelConfigService.chatOptions(modelId))
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
            long generationMs = elapsedMs(generationStartedAt);
            String answer = citationVerifierEnabled
                    ? citationVerifier.enforce(generated, ledger, citationVerifierStrict)
                    : generated;
            FinancialCitationVerifier.Audit audit = citationVerifier.verify(answer, ledger);
            return new FinancialAnalysisResult(answer, plan.resolvedQuestion(), plan.intent(),
                    analysisTasks(plan), analysisEvidence(ledger), analysisFacts(ledger), analysisCalculations(ledger),
                    new CitationAudit(audit.valid(), audit.issues().stream().map(FinancialCitationVerifier.Issue::message).toList()),
                    planningMs, retrievalMs, generationMs, elapsedMs(startedAt), "");
        } catch (Exception exception) {
            log.warn("Financial structured analysis failed: {}", exception.getMessage());
            return FinancialAnalysisResult.failed(exception.getMessage());
        }
    }

    private List<AnalysisTask> analysisTasks(FinancialRetrievalPlan plan) {
        return plan.subTasks().stream().map(task -> new AnalysisTask(
                task.id(), task.query(), task.companies(), task.years(), task.metric(),
                task.operation(), task.modality(), task.dependsOn())).toList();
    }

    private List<AnalysisEvidence> analysisEvidence(FinancialEvidenceLedger.Ledger ledger) {
        return ledger.evidence().stream().map(document -> new AnalysisEvidence(
                document.evidenceId(), document.chunkId(), document.sourceFile(), document.company(),
                document.fiscalYear(), document.modality(), document.item(), document.sectionTitle(),
                document.matchedTaskIds())).toList();
    }

    private List<AnalysisFact> analysisFacts(FinancialEvidenceLedger.Ledger ledger) {
        return ledger.facts().stream().map(fact -> new AnalysisFact(
                fact.factId(), fact.evidenceId(), fact.company(), fact.fiscalYear(), fact.metric(),
                fact.rawValue(), fact.value().toPlainString(), fact.unit(), fact.scale(), fact.quote())).toList();
    }

    private List<AnalysisCalculation> analysisCalculations(FinancialEvidenceLedger.Ledger ledger) {
        return ledger.calculations().stream().map(calculation -> new AnalysisCalculation(
                calculation.calculationId(), calculation.type(), calculation.expression(),
                calculation.displayResult(), calculation.unit(), calculation.scale(), calculation.sourceFactIds())).toList();
    }

    private FinancialRetrievalPlan planRetrieval(String prompt, String conversationId, String modelId) {
        try {
            String planned = ChatClient.builder(modelConfigService.chatModel(modelId))
                    .defaultSystem("""
                            You prepare retrieval queries for a SEC 10-K financial RAG system.
                            Do more than translation: resolve omitted company/year/metric from recent conversation,
                            infer intent, rewrite the question for retrieval, expand important synonyms, and decompose
                            comparison or calculation questions into focused sub-queries.
                            Output strict JSON only. Do not answer the question.
                            """)
                    .build()
                    .prompt()
                    .user("""
                            Recent conversation:
                            %s

                            Current user question:
                            %s

                            Return JSON with this schema:
                            {
                              "translatedQuestion": "concise English translation",
                              "resolvedQuestion": "self-contained English question with omitted company/year/metric filled when inferable",
                              "intent": "one of: factual_metric, comparison, trend, calculation, definition, risk_factor, segment_breakdown, source_lookup, unknown",
                              "subTasks": [
                                {
                                  "id": "stable task id such as retrieve_aapl_2022_revenue",
                                  "query": "focused retrieval query for exactly one financial fact",
                                  "companies": ["SEC ticker"],
                                  "years": ["fiscal year"],
                                  "metric": "canonical financial metric",
                                  "operation": "retrieve | compare | trend | calculate",
                                  "modality": "text | table | hybrid",
                                  "dependsOn": ["ids of prerequisite tasks"]
                                }
                              ],
                              "retrievalQueries": [
                                "best standalone query for semantic/vector retrieval",
                                "focused sub-query for metric/table/period/company",
                                "optional comparison or calculation sub-query"
                              ]
                            }

                            Rules:
                            - Preserve tickers, company names, fiscal years, periods, segments, table row names, accounting terms, and numbers.
                            - If a pronoun or omission can be resolved from recent conversation, fill it in.
                            - If it cannot be resolved, keep the ambiguity explicit in resolvedQuestion.
                            - Prefer English retrieval queries because SEC filings are indexed in English.
                            - For cross-company or cross-year questions, create one retrieve sub-task per company/year/metric fact.
                            - Keep calculation/comparison nodes dependent on the retrieve nodes; do not combine all entities into one retrieve task.
                            - Return exactly 5 retrievalQueries.
                            - retrievalQueries[0] must be the direct concise English translation of the current user question.
                            - retrievalQueries[1] should be the resolved standalone question with omitted context filled in.
                            - retrievalQueries[2..4] should be expanded, decomposed, or metric/table-focused retrieval queries.
                            """.formatted(recentConversationContext(conversationId), prompt))
                    .options(modelConfigService.chatOptions(modelId))
                    .call()
                    .content();
            return parseRetrievalPlan(prompt, planned);
        } catch (Exception ignored) {
            return fallbackRetrievalPlan(prompt);
        }
    }

    private List<ModelStreamEvent> eventsFromResponse(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return List.of();
        }
        Generation generation = response.getResult();
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return List.of();
        }
        List<ModelStreamEvent> events = new ArrayList<>();
        String reasoning = reasoningText(output);
        if (!reasoning.isEmpty()) {
            events.add(new ModelStreamEvent("reasoning", reasoning));
        }
        String content = output.getText();
        if (content != null && !content.isEmpty()) {
            events.add(new ModelStreamEvent("token", content));
        }
        return events;
    }

    private String reasoningText(AssistantMessage output) {
        if (output == null || output.getMetadata() == null) {
            return "";
        }
        Object value = output.getMetadata().get("reasoningContent");
        if (value == null) {
            value = output.getMetadata().get("reasoning_content");
        }
        return value == null ? "" : String.valueOf(value);
    }

    FinancialRetrievalPlan parseRetrievalPlan(String prompt, String planned) {
        if (planned == null || planned.isBlank()) {
            return fallbackRetrievalPlan(prompt);
        }
        try {
            String json = extractJsonObject(planned);
            JsonNode root = objectMapper.readTree(json);
            String translated = cleanLine(root.path("translatedQuestion").asText(""));
            String resolved = cleanLine(root.path("resolvedQuestion").asText(""));
            String intent = cleanLine(root.path("intent").asText("unknown"));
            List<FinancialRetrievalTask> subTasks = parseSubTasks(root.path("subTasks"));
            List<String> queries = new ArrayList<>();
            FinancialRetrievalPlan fallback = fallbackRetrievalPlan(prompt);
            addQuery(queries, translated.isBlank() ? fallback.translatedQuestion() : translated);
            JsonNode queryNodes = root.path("retrievalQueries");
            if (queryNodes.isArray()) {
                for (JsonNode queryNode : queryNodes) {
                    addQuery(queries, queryNode.asText(""));
                }
            }
            addQuery(queries, resolved);
            addQuery(queries, translated);
            for (String query : fallback.queries()) {
                addQuery(queries, query);
            }
            queries = normalizeQueryCount(queries);
            return new FinancialRetrievalPlan(
                    translated.isBlank() ? fallback.translatedQuestion() : translated,
                    resolved.isBlank() ? fallback.resolvedQuestion() : resolved,
                    intent.isBlank() ? "unknown" : intent,
                    queries,
                    subTasks.isEmpty() ? fallback.subTasks() : subTasks);
        } catch (Exception ignored) {
            return fallbackRetrievalPlan(prompt);
        }
    }

    private FinancialRetrievalPlan fallbackRetrievalPlan(String prompt) {
        String normalized = cleanLine(prompt);
        List<String> queries = new ArrayList<>();
        addQuery(queries, normalized);
        String expanded = expandQueryTerms(normalized);
        addQuery(queries, expanded);
        addQuery(queries, normalized + " annual report 10-K");
        addQuery(queries, normalized + " consolidated financial statements");
        addQuery(queries, normalized + " table fiscal year period");
        return new FinancialRetrievalPlan(normalized, normalized, inferIntent(normalized), normalizeQueryCount(queries),
                List.of(new FinancialRetrievalTask("retrieve_1", expanded, List.of(), extractYears(normalized),
                        "", "retrieve", "hybrid", List.of())));
    }

    private List<FinancialRetrievalTask> parseSubTasks(JsonNode nodes) {
        if (!nodes.isArray()) {
            return List.of();
        }
        List<FinancialRetrievalTask> tasks = new ArrayList<>();
        for (JsonNode node : nodes) {
            String query = cleanLine(node.path("query").asText(""));
            if (query.isBlank()) {
                continue;
            }
            tasks.add(new FinancialRetrievalTask(
                    cleanLine(node.path("id").asText("task_" + (tasks.size() + 1))),
                    query,
                    stringList(node.path("companies")),
                    stringList(node.path("years")),
                    cleanLine(node.path("metric").asText("")),
                    cleanLine(node.path("operation").asText("retrieve")),
                    cleanLine(node.path("modality").asText("hybrid")),
                    stringList(node.path("dependsOn"))));
            if (tasks.size() >= 24) {
                break;
            }
        }
        return List.copyOf(tasks);
    }

    private List<String> stringList(JsonNode nodes) {
        if (!nodes.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : nodes) {
            String value = cleanLine(node.asText(""));
            if (!value.isBlank() && values.stream().noneMatch(existing -> existing.equalsIgnoreCase(value))) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private List<String> extractYears(String query) {
        List<String> years = new ArrayList<>();
        Matcher matcher = YEAR_PATTERN.matcher(query == null ? "" : query);
        while (matcher.find()) {
            if (!years.contains(matcher.group())) {
                years.add(matcher.group());
            }
        }
        return List.copyOf(years);
    }

    private String recentConversationContext(String conversationId) {
        try {
            List<Message> messages = chatMemory.get(conversationId);
            if (messages == null || messages.isEmpty()) {
                return "(none)";
            }
            int start = Math.max(0, messages.size() - 8);
            StringBuilder context = new StringBuilder();
            for (Message message : messages.subList(start, messages.size())) {
                String text = cleanLine(message.getText());
                if (text.isBlank()) {
                    continue;
                }
                context.append(message.getMessageType().name().toLowerCase(Locale.ROOT))
                        .append(": ")
                        .append(truncateEnd(text, 500))
                        .append('\n');
            }
            String value = context.toString().strip();
            return value.isBlank() ? "(none)" : value;
        } catch (Exception ignored) {
            return "(none)";
        }
    }

    private String extractJsonObject(String value) {
        String text = value.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private void addQuery(List<String> queries, String query) {
        String normalized = cleanLine(query);
        if (!normalized.isBlank() && queries.stream().noneMatch(existing -> existing.equalsIgnoreCase(normalized))) {
            queries.add(normalized);
        }
    }

    private List<String> normalizeQueryCount(List<String> queries) {
        List<String> normalized = new ArrayList<>();
        for (String query : queries) {
            addQuery(normalized, query);
            if (normalized.size() >= RETRIEVAL_QUERY_COUNT) {
                return List.copyOf(normalized.subList(0, RETRIEVAL_QUERY_COUNT));
            }
        }
        String seed = normalized.isEmpty() ? "financial annual report query" : normalized.get(0);
        addQuery(normalized, seed + " SEC 10-K annual report");
        addQuery(normalized, seed + " consolidated financial statements table");
        addQuery(normalized, seed + " fiscal year period");
        addQuery(normalized, seed + " management discussion analysis");
        addQuery(normalized, seed + " notes to consolidated financial statements");
        return List.copyOf(normalized.subList(0, Math.min(RETRIEVAL_QUERY_COUNT, normalized.size())));
    }

    private String expandQueryTerms(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<String> additions = new ArrayList<>();
        Map<String, String> metricSynonyms = Map.ofEntries(
                Map.entry("revenue", "net sales sales revenue"),
                Map.entry("net sales", "revenue sales"),
                Map.entry("net income", "net earnings profit"),
                Map.entry("operating income", "income from operations operating profit"),
                Map.entry("total assets", "assets consolidated balance sheets"),
                Map.entry("current assets", "current assets consolidated balance sheets"),
                Map.entry("cash", "cash and cash equivalents"),
                Map.entry("liabilities", "total liabilities current liabilities"),
                Map.entry("gross margin", "gross profit net sales"),
                Map.entry("eps", "earnings per share diluted basic")
        );
        for (Map.Entry<String, String> entry : metricSynonyms.entrySet()) {
            if (lower.contains(entry.getKey())) {
                additions.add(entry.getValue());
            }
        }
        return additions.isEmpty() ? query : query + " " + String.join(" ", additions);
    }

    private String inferIntent(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("compare") || lower.contains("vs") || lower.contains("change") || lower.contains("increase")
                || lower.contains("decrease") || lower.contains("相比") || lower.contains("变化")) {
            return "comparison";
        }
        if (lower.contains("ratio") || lower.contains("margin") || lower.contains("calculate") || lower.contains("difference")
                || lower.contains("计算")) {
            return "calculation";
        }
        if (lower.contains("risk")) {
            return "risk_factor";
        }
        if (lower.contains("segment")) {
            return "segment_breakdown";
        }
        return "factual_metric";
    }

    private String retrievalPlanPrompt(FinancialRetrievalPlan plan) {
        return """

                Retrieval preprocessing:
                - resolvedQuestion: %s
                - intent: %s
                - retrievalQueries: %s
                - typedSubTasks: %s
                """.formatted(plan.resolvedQuestion(), plan.intent(), plan.displayQuery(), plan.subTasks());
    }

    private FinancialEvidenceLedger.Ledger buildEvidenceLedger(String prompt,
                                                                FinancialRetrievalPlan plan,
                                                                List<FinancialChunk> chunks,
                                                                FinancialRetrievalMode retrievalMode,
                                                                String modelId) {
        List<FinancialEvidenceLedger.EvidenceDocument> documents = new ArrayList<>();
        int documentLimit = Math.min(chunks.size(), Math.max(1, evidenceLedgerMaxDocuments));
        for (int index = 0; index < documentLimit; index++) {
            FinancialChunk chunk = chunks.get(index);
            String company = chunk.metadataText("company");
            String year = chunk.metadataText("year");
            if ((company.isBlank() || year.isBlank()) && chunk.sourceFile().contains("_")) {
                String stem = chunk.sourceFile().replaceFirst("(?i)\\.html$", "");
                int separator = stem.lastIndexOf('_');
                if (separator > 0) {
                    company = company.isBlank() ? stem.substring(0, separator) : company;
                    year = year.isBlank() ? stem.substring(separator + 1) : year;
                }
            }
            documents.add(new FinancialEvidenceLedger.EvidenceDocument(
                    "E" + (index + 1), chunk.chunkId(), chunk.sourceFile(), company, year,
                    chunk.chunkType(), chunk.metadataText("item"), chunk.metadataText("section_title"),
                    truncateEnd(retrievalContent(chunk, retrievalMode), Math.max(500, evidenceLedgerMaxContentChars)),
                    List.copyOf(chunk.matchedTaskIds)));
        }
        if (!evidenceLedgerEnabled) {
            return evidenceLedger.evidenceOnly(documents);
        }
        try {
            String extraction = ChatClient.builder(modelConfigService.chatModel(modelId))
                    .defaultSystem("""
                            You extract auditable numerical facts from SEC 10-K evidence.
                            Never answer the question, calculate, estimate, or use outside knowledge.
                            Every extracted fact must contain an exact quote from one supplied evidence block.
                            Output strict JSON only.
                            """)
                    .build()
                    .prompt()
                    .user(evidenceLedger.extractionPrompt(plan.resolvedQuestion(), documents))
                    .options(modelConfigService.chatOptions(modelId))
                    .call()
                    .content();
            return evidenceLedger.build(prompt, documents, extraction);
        } catch (Exception exception) {
            log.warn("Financial evidence extraction failed: {}", exception.getMessage());
            return evidenceLedger.evidenceOnly(documents);
        }
    }

    private String answerPrompt(String prompt,
                                FinancialRetrievalPlan plan,
                                FinancialEvidenceLedger.Ledger ledger) {
        return """
                用户问题：
                %s

                %s

                回答规则：
                1. 只能使用 Evidence Ledger 中的信息，不能补充外部知识。
                2. 直接数值优先使用已校验事实 [F#]；涉及运算时只能使用确定性计算 [C#]，不得自行心算或改写计算结果，并在计算结论后明确标记对应 [C#]。
                3. 如果缺少完成比较或计算所需的事实，明确指出缺少哪家公司、财年或指标，不得猜测。
                4. 每项关键结论后引用原始证据编号 [E#]；引用必须与结论来自同一公司和财年。每个引用必须使用独立方括号，例如 [F1][E2]，不要写成 [F1；E2]。
                5. 涉及跨公司或跨财年比较时，先按“公司—财年—指标”列出事实，再给出比较结论。
                6. 结尾给出“来源”列表，格式为 `[E#] source_file — section`，不要伪造页码。
                7. 如果问题询问某信息是否披露或是否缺失，必须明确回答；只有检索结果中包含对应公司和财年的证据时，
                   才能说明“未披露/未提及”，并在该结论后引用对应财年的原始证据 [E#]。
                8. 使用与用户问题相同的语言回答：英文问题用英文，中文问题用中文；公司名、指标名和原始引文保持原文。
                """.formatted(prompt, ledger.render()) + retrievalPlanPrompt(plan);
    }

    private Flux<String> verifyAnswer(Flux<String> answer, FinancialEvidenceLedger.Ledger ledger) {
        if (!citationVerifierEnabled) {
            return answer;
        }
        return answer.collectList().flatMapMany(parts -> Flux.just(
                citationVerifier.enforce(String.join("", parts), ledger, citationVerifierStrict)));
    }

    private Flux<ModelStreamEvent> verifyAnswerEvents(Flux<ModelStreamEvent> events,
                                                       FinancialEvidenceLedger.Ledger ledger) {
        if (!citationVerifierEnabled) {
            return events;
        }
        return events.collectList().flatMapMany(parts -> {
            List<ModelStreamEvent> verified = new ArrayList<>();
            parts.stream().filter(event -> "reasoning".equals(event.type())).forEach(verified::add);
            String answer = parts.stream().filter(event -> "token".equals(event.type()))
                    .map(ModelStreamEvent::content).collect(java.util.stream.Collectors.joining());
            verified.add(new ModelStreamEvent("token", citationVerifier.enforce(answer, ledger, citationVerifierStrict)));
            return Flux.fromIterable(verified);
        });
    }

    private ChatClient financeChatClient(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return financeChatClient;
        }
        return ChatClient.builder(modelConfigService.chatModel(modelId))
                .defaultSystem("""
                        你是金融年报 RAG 问答助手。只能基于用户消息中的检索上下文回答 SEC 10-K 年报问题。
                        优先直接给出结论；涉及表格或财务计算时，必须使用上下文中的公司、年份、period、表头和行名。
                        如果上下文不足以确定答案，明确说明无法从当前上下文确定，不要编造。
                        """)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    private long elapsedMs(long startedAtMs) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    private List<FinancialChunk> search(FinancialRetrievalPlan plan, FinancialRetrievalMode retrievalMode) {
        List<FinancialSearchRequest> requests = taskScopedRequests(plan);
        if (requests.isEmpty()) {
            requests = List.of(new FinancialSearchRequest(plan.displayQuery(), "", "", "hybrid", ""));
        }
        int finalTopK = Math.max(1, topK);
        Map<String, FinancialChunk> merged = new LinkedHashMap<>();
        Map<String, FinancialChunk> scopeAnchors = new LinkedHashMap<>();
        for (int index = 0; index < requests.size(); index++) {
            FinancialSearchRequest request = requests.get(index);
            String query = request.query();
            if (query == null || query.isBlank()) {
                continue;
            }
            int limit = index == 0 ? Math.max(finalTopK, hybridTopK) : finalTopK;
            List<FinancialChunk> requestChunks = search(query, limit, retrievalMode, request.company(), request.year(),
                    request.modality(), false).stream().map(chunk -> chunk.withMatchedTask(request.taskId())).toList();
            if ((!request.company().isBlank() || !request.year().isBlank()) && !requestChunks.isEmpty()) {
                String scope = request.taskId().isBlank()
                        ? request.company().toUpperCase(Locale.ROOT) + "|" + request.year() + "|"
                        + cleanLine(request.query()).toLowerCase(Locale.ROOT)
                        : request.taskId();
                FinancialChunk anchor = requestChunks.get(0);
                FinancialChunk existingAnchor = scopeAnchors.get(scope);
                if (existingAnchor == null || anchor.finalScore() > existingAnchor.finalScore()) {
                    scopeAnchors.put(scope, anchor);
                }
            }
            for (FinancialChunk chunk : requestChunks) {
                FinancialChunk existing = merged.get(chunk.chunkId());
                if (existing == null || chunk.finalScore() > existing.finalScore()) {
                    if (existing != null) {
                        chunk.matchedTaskIds.addAll(existing.matchedTaskIds);
                    }
                    merged.put(chunk.chunkId(), chunk);
                } else {
                    existing.matchedTaskIds.addAll(chunk.matchedTaskIds);
                }
            }
        }
        List<FinancialChunk> scoreRanked = merged.values().stream()
                .sorted(Comparator.comparingDouble(FinancialChunk::finalScore).reversed())
                .toList();
        int candidateLimit = Math.max(finalTopK, Math.min(hybridTopK, Math.max(finalTopK, rerankCandidateLimit)));
        Map<String, FinancialChunk> candidates = new LinkedHashMap<>();
        scopeAnchors.values().stream()
                .sorted(Comparator.comparingDouble(FinancialChunk::finalScore).reversed())
                .limit(candidateLimit)
                .forEach(chunk -> candidates.put(chunk.chunkId(), chunk));
        for (FinancialChunk chunk : scoreRanked) {
            if (candidates.size() >= candidateLimit) {
                break;
            }
            candidates.putIfAbsent(chunk.chunkId(), chunk);
        }
        List<FinancialChunk> globallyRanked = List.copyOf(candidates.values());
        if (rerankEnabled && !globallyRanked.isEmpty()) {
            globallyRanked = rerank(plan.resolvedQuestion(), globallyRanked, candidateLimit, retrievalMode);
        }
        return taskBalancedTopK(globallyRanked, plan.subTasks(), finalTopK);
    }

    private List<FinancialChunk> taskBalancedTopK(List<FinancialChunk> ranked,
                                                   List<FinancialRetrievalTask> tasks,
                                                   int limit) {
        Map<String, FinancialChunk> selected = new LinkedHashMap<>();
        for (FinancialRetrievalTask task : tasks) {
            if (!"retrieve".equalsIgnoreCase(task.operation())) {
                continue;
            }
            ranked.stream().filter(chunk -> chunk.matchedTaskIds.contains(task.id())).findFirst()
                    .ifPresent(chunk -> selected.putIfAbsent(chunk.chunkId(), chunk));
            if (selected.size() >= limit) {
                return List.copyOf(selected.values());
            }
        }
        for (FinancialChunk chunk : ranked) {
            selected.putIfAbsent(chunk.chunkId(), chunk);
            if (selected.size() >= limit) {
                break;
            }
        }
        return List.copyOf(selected.values());
    }

    private List<FinancialSearchRequest> taskScopedRequests(FinancialRetrievalPlan plan) {
        List<FinancialSearchRequest> requests = new ArrayList<>();
        for (FinancialRetrievalTask task : plan.subTasks()) {
            if (!"retrieve".equalsIgnoreCase(task.operation()) || task.query().isBlank()) {
                continue;
            }
            List<String> companies = task.companies().isEmpty() ? List.of("") : task.companies();
            List<String> years = task.years().isEmpty() ? List.of("") : task.years();
            for (String company : companies) {
                for (String year : years) {
                    addSearchRequest(requests, new FinancialSearchRequest(
                            String.join(" ", List.of(task.query(), task.metric())).strip(),
                            company.toUpperCase(Locale.ROOT), year, task.modality(), task.id()));
                }
            }
        }
        for (String query : plan.queries()) {
            addSearchRequest(requests, new FinancialSearchRequest(query, "", "", "hybrid", ""));
        }
        return List.copyOf(requests);
    }

    private void addSearchRequest(List<FinancialSearchRequest> requests, FinancialSearchRequest candidate) {
        String query = cleanLine(candidate.query());
        if (query.isBlank()) {
            return;
        }
        FinancialSearchRequest normalized = new FinancialSearchRequest(query, cleanLine(candidate.company()),
                cleanLine(candidate.year()), normalizeModality(candidate.modality()), cleanLine(candidate.taskId()));
        boolean duplicate = requests.stream().anyMatch(existing ->
                existing.query().equalsIgnoreCase(normalized.query())
                        && existing.company().equalsIgnoreCase(normalized.company())
                        && existing.year().equalsIgnoreCase(normalized.year())
                        && existing.modality().equalsIgnoreCase(normalized.modality())
                        && existing.taskId().equalsIgnoreCase(normalized.taskId()));
        if (!duplicate) {
            requests.add(normalized);
        }
    }

    private List<FinancialChunk> search(String query) {
        return search(query, Math.max(1, topK), FinancialRetrievalMode.fromConfig(retrievalStrategy));
    }

    private List<FinancialChunk> search(String query, int resultLimit) {
        return search(query, resultLimit, FinancialRetrievalMode.fromConfig(retrievalStrategy));
    }

    private List<FinancialChunk> search(String query, int resultLimit, FinancialRetrievalMode retrievalMode) {
        String table = resolveTableName();
        return search(query, resultLimit, retrievalMode, table, inferFilters(query, table));
    }

    private List<FinancialChunk> search(String query, int resultLimit, FinancialRetrievalMode retrievalMode,
                                        String company, String year) {
        return search(query, resultLimit, retrievalMode, company, year, "hybrid");
    }

    private List<FinancialChunk> search(String query, int resultLimit, FinancialRetrievalMode retrievalMode,
                                        String company, String year, String modality) {
        return search(query, resultLimit, retrievalMode, company, year, modality, true);
    }

    private List<FinancialChunk> search(String query, int resultLimit, FinancialRetrievalMode retrievalMode,
                                        String company, String year, String modality, boolean applyRerank) {
        String table = resolveTableName();
        RetrievalFilters filters = explicitFilters(table, company, year, modality);
        if (filters.isEmpty()) {
            filters = inferFilters(query, table);
        }
        return search(query, resultLimit, retrievalMode, table, filters, applyRerank);
    }

    private List<FinancialChunk> search(String query, int resultLimit, FinancialRetrievalMode retrievalMode,
                                        String table, RetrievalFilters filters) {
        return search(query, resultLimit, retrievalMode, table, filters, true);
    }

    private List<FinancialChunk> search(String query, int resultLimit, FinancialRetrievalMode retrievalMode,
                                        String table, RetrievalFilters filters, boolean applyRerank) {
        float[] embedding = embeddingModel.embed(query);
        String vector = vectorLiteral(embedding);
        int finalTopK = Math.max(1, resultLimit);
        int finalHybridTopK = Math.max(hybridTopK, finalTopK);
        int vectorLimit = Math.max(finalHybridTopK * 3, finalTopK);
        List<FinancialChunk> vectorRows = fetchVectorCandidates(table, vector, filters, vectorLimit);
        List<FinancialChunk> candidateRows = filters.hasNarrowScope()
                ? fetchMetadataCandidates(table, filters)
                : fetchLexicalCandidates(table, filters, query, Math.max(finalHybridTopK * 20, 500));
        if (candidateRows.isEmpty() && filters.hasCompanyOrYear()) {
            RetrievalFilters fallbackFilters = filters.withoutSourceFile();
            vectorRows = fetchVectorCandidates(table, vector, fallbackFilters, vectorLimit);
            candidateRows = fetchMetadataCandidates(table, fallbackFilters);
        }
        if (candidateRows.isEmpty()) {
            candidateRows = vectorRows;
        }

        List<FinancialChunk> vectorRanked = rankVectorRows(vectorRows, finalHybridTopK);
        List<FinancialChunk> bm25Ranked = rankBm25Rows(candidateRows, query, finalHybridTopK);
        List<FinancialChunk> ranked = hybridRrf(vectorRanked, bm25Ranked, finalHybridTopK);
        if (retrievalMode == FinancialRetrievalMode.PARENT_CHILD) {
            ranked = collapseParentChildRows(ranked, finalHybridTopK);
        }
        if (applyRerank && rerankEnabled) {
            ranked = rerank(query, ranked, finalTopK, retrievalMode);
        }
        return ranked.stream().limit(finalTopK).toList();
    }

    private RetrievalFilters explicitFilters(String table, String company, String year, String modality) {
        String normalizedCompany = cleanLine(company).toUpperCase(Locale.ROOT);
        String normalizedYear = cleanLine(year);
        String sourceFile = "";
        if (!normalizedCompany.isBlank() && !normalizedYear.isBlank()) {
            String candidate = normalizedCompany + "_" + normalizedYear + ".html";
            if (sourceFileExists(table, candidate)) {
                sourceFile = candidate;
            }
        }
        String chunkType = switch (normalizeModality(modality)) {
            case "text" -> "text";
            case "table" -> "table";
            default -> "";
        };
        return new RetrievalFilters(sourceFile, normalizedCompany, normalizedYear, chunkType);
    }

    private String resolveTableName() {
        if (tableExists(tableName)) {
            return safeTableName(tableName);
        }
        if (tableExists(fallbackTableName)) {
            return safeTableName(fallbackTableName);
        }
        if (tableExists(legacyFallbackTableName)) {
            return safeTableName(legacyFallbackTableName);
        }
        return safeTableName(tableName);
    }

    private boolean tableExists(String table) {
        String safe = safeTableName(table);
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = ?
                )
                """, Boolean.class, safe);
        return Boolean.TRUE.equals(exists);
    }

    private RetrievalFilters inferFilters(String query, String table) {
        String normalized = query.toLowerCase(Locale.ROOT);
        String company = inferCompany(query, normalized, table).orElse("");
        String year = inferYear(query).orElse("");
        String sourceFile = "";
        if (!company.isBlank() && !year.isBlank()) {
            String candidate = company + "_" + year + ".html";
            if (sourceFileExists(table, candidate)) {
                sourceFile = candidate;
            }
        }
        return new RetrievalFilters(sourceFile, company, year, "");
    }

    private Optional<String> inferCompany(String query, String normalized, String table) {
        for (Map.Entry<String, String> entry : COMPANY_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }

        Set<String> companies = knownCompanies(table);
        for (String token : query.split("[^A-Za-z0-9-]+")) {
            String candidate = token.toUpperCase(Locale.ROOT);
            if (companies.contains(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Set<String> knownCompanies(String table) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT company FROM " + safeTableName(table) + " ORDER BY company",
                String.class
        ));
    }

    private Optional<String> inferYear(String query) {
        Matcher matcher = YEAR_PATTERN.matcher(query);
        if (matcher.find()) {
            return Optional.of(matcher.group());
        }
        return Optional.empty();
    }

    private boolean sourceFileExists(String table, String sourceFile) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + safeTableName(table) + " WHERE source_file = ?",
                Integer.class,
                sourceFile
        );
        return count != null && count > 0;
    }

    private List<FinancialChunk> fetchVectorCandidates(String table, String vector, RetrievalFilters filters, int limit) {
        SqlWhere where = whereClause(filters);
        List<Object> params = new ArrayList<>();
        params.add(vector);
        params.addAll(where.params());
        params.add(vector);
        params.add(limit);

        String sql = """
                SELECT chunk_id, source_file, chunk_type, content, metadata::text AS metadata,
                       1 - (embedding <=> ?::vector) AS score
                FROM %s
                %s
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(safeTableName(table), where.sql());
        return jdbcTemplate.query(sql, this::mapChunk, params.toArray());
    }

    private List<FinancialChunk> fetchMetadataCandidates(String table, RetrievalFilters filters) {
        SqlWhere where = whereClause(filters);
        String sql = """
                SELECT chunk_id, source_file, chunk_type, content, metadata::text AS metadata, 0.0 AS score
                FROM %s
                %s
                ORDER BY source_file, chunk_index
                LIMIT ?
                """.formatted(safeTableName(table), where.sql());
        List<Object> params = new ArrayList<>(where.params());
        params.add(filters.isEmpty() ? 2000 : 20000);
        return jdbcTemplate.query(sql, this::mapChunk, params.toArray());
    }

    private List<FinancialChunk> fetchLexicalCandidates(String table, RetrievalFilters filters,
                                                         String query, int limit) {
        String tsQuery = lexicalTsQuery(query);
        if (tsQuery.isBlank()) {
            return fetchMetadataCandidates(table, filters);
        }
        SqlWhere where = whereClause(filters);
        String prefix = where.sql().isBlank() ? "WHERE " : where.sql() + " AND ";
        String sql = """
                SELECT chunk_id, source_file, chunk_type, content, metadata::text AS metadata,
                       ts_rank_cd(to_tsvector('english', content), to_tsquery('english', ?)) AS score
                FROM %s
                %s to_tsvector('english', content) @@ to_tsquery('english', ?)
                ORDER BY score DESC
                LIMIT ?
                """.formatted(safeTableName(table), prefix);
        List<Object> params = new ArrayList<>();
        params.add(tsQuery);
        params.addAll(where.params());
        params.add(tsQuery);
        params.add(Math.max(1, limit));
        return jdbcTemplate.query(sql, this::mapChunk, params.toArray());
    }

    String lexicalTsQuery(String query) {
        return tokenize(query).stream()
                .map(token -> token.replaceAll("[^a-z0-9]", ""))
                .filter(token -> !token.isBlank())
                .distinct()
                .limit(16)
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    SqlWhere whereClause(RetrievalFilters filters) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (!filters.sourceFile().isBlank()) {
            conditions.add("source_file = ?");
            params.add(filters.sourceFile());
        }
        if (!filters.company().isBlank()) {
            conditions.add("company = ?");
            params.add(filters.company());
        }
        if (!filters.year().isBlank()) {
            conditions.add("year = ?");
            params.add(filters.year());
        }
        if (!filters.chunkType().isBlank()) {
            conditions.add("chunk_type = ?");
            params.add(filters.chunkType());
        }
        return new SqlWhere(conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions), params);
    }

    private FinancialChunk mapChunk(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Map<String, Object> metadata = readMetadata(rs.getString("metadata"));
        return new FinancialChunk(
                rs.getString("chunk_id"),
                rs.getString("source_file"),
                rs.getString("chunk_type"),
                rs.getString("content"),
                metadata,
                rs.getDouble("score")
        );
    }

    private List<FinancialChunk> rankVectorRows(List<FinancialChunk> rows, int limit) {
        List<FinancialChunk> ranked = new ArrayList<>(rows);
        ranked.sort(Comparator
                .comparingDouble((FinancialChunk chunk) -> chunk.score()
                        + ("table".equals(chunk.chunkType()) ? 0.04 : 0.0)
                        + ("Item 8".equals(chunk.metadataText("item")) ? 0.02 : 0.0))
                .reversed());
        for (int index = 0; index < Math.min(limit, ranked.size()); index++) {
            ranked.get(index).vectorRank = index + 1;
            ranked.get(index).vectorScore = ranked.get(index).score();
        }
        return ranked.stream().limit(limit).toList();
    }

    private List<FinancialChunk> rankBm25Rows(List<FinancialChunk> rows, String query, int limit) {
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<List<String>> documentTerms = rows.stream()
                .map(row -> tokenize(searchableText(row)))
                .toList();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> terms : documentTerms) {
            for (String term : new LinkedHashSet<>(terms)) {
                documentFrequency.put(term, documentFrequency.getOrDefault(term, 0) + 1);
            }
        }

        double averageLength = documentTerms.stream().mapToInt(List::size).average().orElse(1.0);
        List<FinancialChunk> ranked = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            FinancialChunk chunk = rows.get(index).copy();
            double score = bm25Score(queryTerms, documentTerms.get(index), documentFrequency, rows.size(), averageLength);
            score += keywordBoost(chunk, query, queryTerms);
            if (score <= 0) {
                continue;
            }
            chunk.bm25Score = score;
            ranked.add(chunk);
        }
        ranked.sort(Comparator.comparingDouble((FinancialChunk chunk) -> chunk.bm25Score).reversed());
        for (int index = 0; index < Math.min(limit, ranked.size()); index++) {
            ranked.get(index).bm25Rank = index + 1;
        }
        return ranked.stream().limit(limit).toList();
    }

    private List<FinancialChunk> hybridRrf(List<FinancialChunk> vectorRows, List<FinancialChunk> bm25Rows, int limit) {
        Map<String, FinancialChunk> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        double k = 60.0;
        for (int index = 0; index < vectorRows.size(); index++) {
            FinancialChunk chunk = vectorRows.get(index).copy();
            byId.putIfAbsent(chunk.chunkId(), chunk);
            scores.put(chunk.chunkId(), scores.getOrDefault(chunk.chunkId(), 0.0) + 1.0 / (k + index + 1));
        }
        for (int index = 0; index < bm25Rows.size(); index++) {
            FinancialChunk chunk = bm25Rows.get(index);
            FinancialChunk target = byId.computeIfAbsent(chunk.chunkId(), ignored -> chunk.copy());
            target.bm25Score = chunk.bm25Score;
            target.bm25Rank = chunk.bm25Rank;
            scores.put(chunk.chunkId(), scores.getOrDefault(chunk.chunkId(), 0.0) + 1.0 / (k + index + 1));
        }
        List<FinancialChunk> ranked = new ArrayList<>();
        for (Map.Entry<String, FinancialChunk> entry : byId.entrySet()) {
            FinancialChunk chunk = entry.getValue();
            chunk.hybridScore = scores.getOrDefault(entry.getKey(), 0.0);
            chunk.finalScore = chunk.hybridScore;
            ranked.add(chunk);
        }
        ranked.sort(Comparator
                .comparingDouble((FinancialChunk chunk) -> chunk.hybridScore)
                .thenComparingDouble(chunk -> chunk.vectorScore)
                .thenComparing(chunk -> "table".equals(chunk.chunkType()))
                .reversed());
        return ranked.stream().limit(limit).toList();
    }

    private List<FinancialChunk> rerank(String query, List<FinancialChunk> rows, int finalTopK, FinancialRetrievalMode retrievalMode) {
        if (rows.isEmpty()) {
            return rows;
        }
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(trimTrailingSlash(rerankUrl))
                    .requestFactory(new SimpleClientHttpRequestFactory())
                    .build();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("documents", rows.stream().map(row -> rerankDocumentText(row, retrievalMode)).toList());
            body.put("top_n", Math.max(finalTopK, topK));
            body.put("return_documents", false);
            if (rerankModel != null && !rerankModel.isBlank()) {
                body.put("model", rerankModel);
            }

            JsonNode response = client.post()
                    .uri("/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.has("results") || !response.get("results").isArray()) {
                return rows;
            }

            List<FinancialChunk> ranked = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (JsonNode result : response.get("results")) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= rows.size() || seen.contains(index)) {
                    continue;
                }
                FinancialChunk chunk = rows.get(index).copy();
                chunk.rerankScore = result.path("relevance_score").asDouble(0.0);
                chunk.finalScore = chunk.rerankScore;
                ranked.add(chunk);
                seen.add(index);
                if (ranked.size() >= Math.max(finalTopK, topK)) {
                    break;
                }
            }
            for (int index = 0; index < rows.size() && ranked.size() < Math.max(finalTopK, topK); index++) {
                if (!seen.contains(index)) {
                    ranked.add(rows.get(index));
                }
            }
            return ranked;
        } catch (Exception exception) {
            log.warn("Financial reranker request failed: {}", exception.getMessage());
            return rows;
        }
    }

    private String rerankDocumentText(FinancialChunk chunk, FinancialRetrievalMode retrievalMode) {
        String prefix = metadataPrefix(chunk);
        String text = prefix + "\n" + retrievalContent(chunk, retrievalMode);
        if (rerankDocChars > 0 && text.length() > rerankDocChars) {
            return text.substring(0, rerankDocChars).stripTrailing();
        }
        return text;
    }

    private String retrievedContext(List<FinancialChunk> chunks, FinancialRetrievalMode retrievalMode) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < chunks.size(); index++) {
            FinancialChunk chunk = chunks.get(index);
            builder.append('[').append(index + 1).append("] ")
                    .append(metadataPrefix(chunk))
                    .append(" | score=").append(String.format(Locale.ROOT, "%.4f", chunk.finalScore()))
                    .append('\n')
                    .append(retrievalContent(chunk, retrievalMode))
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private String retrievalContent(FinancialChunk chunk, FinancialRetrievalMode retrievalMode) {
        if (chunk == null) {
            return "";
        }
        FinancialRetrievalMode mode = retrievalMode == null ? FinancialRetrievalMode.DEFAULT : retrievalMode;
        if (mode == FinancialRetrievalMode.PARENT_CHILD) {
            String parentContext = chunk.metadataText("parent_context");
            if (!parentContext.isBlank()) {
                return parentContext;
            }
        }
        return chunk.content();
    }

    private List<FinancialChunk> collapseParentChildRows(List<FinancialChunk> rows, int limit) {
        Map<String, FinancialChunk> collapsed = new LinkedHashMap<>();
        for (FinancialChunk row : rows) {
            FinancialChunk current = row.copy();
            String key = parentCollapseKey(current);
            FinancialChunk existing = collapsed.get(key);
            if (existing == null || current.finalScore() > existing.finalScore()) {
                collapsed.put(key, current);
            }
        }
        return collapsed.values().stream()
                .sorted(Comparator.comparingDouble(FinancialChunk::finalScore).reversed())
                .limit(limit)
                .toList();
    }

    private String metadataPrefix(FinancialChunk chunk) {
        List<String> parts = new ArrayList<>();
        parts.add("source=" + chunk.sourceFile());
        parts.add("type=" + chunk.chunkType());
        addMetadata(parts, "company", chunk);
        addMetadata(parts, "year", chunk);
        addMetadata(parts, "item", chunk);
        addMetadata(parts, "section_title", chunk);
        addMetadata(parts, "table_header", chunk);
        addMetadata(parts, "primary_period", chunk);
        Object periods = chunk.metadata().get("periods");
        if (periods instanceof List<?> values && !values.isEmpty()) {
            parts.add("periods=" + String.join("; ", values.stream().map(String::valueOf).toList()));
        }
        return String.join(" | ", parts);
    }

    private String parentCollapseKey(FinancialChunk chunk) {
        String parentId = chunk.metadataText("parent_id");
        if (!parentId.isBlank()) {
            return "parent:" + parentId;
        }
        String parentContext = chunk.metadataText("parent_context");
        if (!parentContext.isBlank()) {
            return "context:" + Integer.toHexString(parentContext.hashCode());
        }
        return "chunk:" + chunk.chunkId();
    }

    private void addMetadata(List<String> parts, String key, FinancialChunk chunk) {
        String value = chunk.metadataText(key);
        if (!value.isBlank()) {
            parts.add(key + "=" + value);
        }
    }

    private String searchableText(FinancialChunk chunk) {
        List<String> values = new ArrayList<>();
        values.add(chunk.content());
        for (String key : List.of("table_header", "section_title", "evidence_section", "company", "year",
                "sector", "gics_sector", "gics_industry", "primary_period")) {
            values.add(chunk.metadataText(key));
        }
        addListMetadata(values, chunk.metadata().get("periods"));
        addListMetadata(values, chunk.metadata().get("period_years"));
        addListMetadata(values, chunk.metadata().get("search_keywords"));
        return String.join(" ", values);
    }

    private void addListMetadata(List<String> values, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> values.add(String.valueOf(item)));
        }
    }

    private List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group().replace(",", "").replaceAll("^[.,;:()\\[\\]{}]+|[.,;:()\\[\\]{}]+$", "");
            if (!token.isBlank() && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private double bm25Score(List<String> queryTerms,
                             List<String> documentTerms,
                             Map<String, Integer> documentFrequency,
                             int documentCount,
                             double averageLength) {
        if (documentTerms.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> termCounts = new HashMap<>();
        documentTerms.forEach(term -> termCounts.put(term, termCounts.getOrDefault(term, 0) + 1));
        double k1 = 1.4;
        double b = 0.72;
        double score = 0.0;
        for (String term : new LinkedHashSet<>(queryTerms)) {
            int tf = termCounts.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1.0 + Math.max(0.0, (documentCount - df + 0.5) / (df + 0.5)));
            double denominator = tf + k1 * (1.0 - b + b * documentTerms.size() / Math.max(averageLength, 1.0));
            score += idf * (tf * (k1 + 1.0)) / denominator;
        }
        return score;
    }

    private double keywordBoost(FinancialChunk chunk, String query, List<String> queryTerms) {
        String question = query.toLowerCase(Locale.ROOT);
        String content = chunk.content().toLowerCase(Locale.ROOT);
        String header = chunk.metadataText("table_header").toLowerCase(Locale.ROOT);
        double boost = 0.0;
        if ("table".equals(chunk.chunkType())) {
            boost += 0.4;
        }
        for (String term : queryTerms) {
            if (header.contains(term)) {
                boost += 0.12;
            }
        }
        for (String phrase : List.of("current assets", "current liabilities", "net income", "income taxes",
                "total assets", "revenue", "net sales", "operating income")) {
            if (question.contains(phrase) && content.contains(phrase)) {
                boost += 0.18;
            }
        }
        return Math.min(boost, 1.4);
    }

    private Map<String, Object> readMetadata(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String vectorLiteral(float[] values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.8f", values[index]));
        }
        return builder.append(']').toString();
    }

    private String safeTableName(String table) {
        String value = table == null || table.isBlank() ? "multidoc_full_chunks" : table;
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid financial RAG table name: " + value);
        }
        return value;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String cleanLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\R+", " ").replaceAll("\\s+", " ");
    }

    private String normalizeModality(String value) {
        String normalized = cleanLine(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "text", "table" -> normalized;
            default -> "hybrid";
        };
    }

    private String truncateEnd(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 24)).strip() + " ...";
    }

    record RetrievalFilters(String sourceFile, String company, String year, String chunkType) {
        boolean isEmpty() {
            return sourceFile.isBlank() && company.isBlank() && year.isBlank() && chunkType.isBlank();
        }

        boolean hasCompanyOrYear() {
            return !company.isBlank() || !year.isBlank();
        }

        boolean hasNarrowScope() {
            return !sourceFile.isBlank() || !company.isBlank() || !year.isBlank();
        }

        RetrievalFilters withoutSourceFile() {
            return new RetrievalFilters("", company, year, chunkType);
        }
    }

    record SqlWhere(String sql, List<Object> params) {
    }

    private record FinancialSearchRequest(String query, String company, String year, String modality, String taskId) {
    }

    record FinancialRetrievalPlan(String translatedQuestion,
                                  String resolvedQuestion,
                                  String intent,
                                  List<String> queries,
                                  List<FinancialRetrievalTask> subTasks) {
        String displayQuery() {
            return String.join(" | ", queries);
        }
    }

    record FinancialRetrievalTask(String id,
                                  String query,
                                  List<String> companies,
                                  List<String> years,
                                  String metric,
                                  String operation,
                                  String modality,
                                  List<String> dependsOn) {
    }

    public record ModelStreamEvent(String type, String content) {
    }

    public record FinancialAnswerStream(Flux<ModelStreamEvent> content, long translationMs, long retrievalMs,
                                        boolean modelRequested, String retrievalQuery) {
    }

    public record AnalysisTask(String id, String query, List<String> companies, List<String> years,
                               String metric, String operation, String modality, List<String> dependsOn) {
    }

    public record AnalysisEvidence(String evidenceId, String chunkId, String sourceFile, String company,
                                   String fiscalYear, String modality, String item, String sectionTitle,
                                   List<String> matchedTaskIds) {
    }

    public record AnalysisFact(String factId, String evidenceId, String company, String fiscalYear, String metric,
                               String rawValue, String value, String unit, String scale, String quote) {
    }

    public record AnalysisCalculation(String calculationId, String type, String expression, String result,
                                      String unit, String scale, List<String> sourceFactIds) {
    }

    public record CitationAudit(boolean valid, List<String> issues) {
    }

    public record FinancialAnalysisResult(String answer, String resolvedQuestion, String intent,
                                          List<AnalysisTask> tasks, List<AnalysisEvidence> evidence,
                                          List<AnalysisFact> facts, List<AnalysisCalculation> calculations,
                                          CitationAudit citationAudit, long planningMs, long retrievalMs,
                                          long generationMs, long totalMs, String error) {
        static FinancialAnalysisResult failed(String error) {
            return new FinancialAnalysisResult("", "", "unknown", List.of(), List.of(), List.of(), List.of(),
                    new CitationAudit(false, List.of(error == null ? "unknown error" : error)),
                    0L, 0L, 0L, 0L, error == null ? "unknown error" : error);
        }
    }

    public enum FinancialRetrievalMode {
        DEFAULT,
        PARENT_CHILD;

        public static FinancialRetrievalMode fromConfig(String value) {
            return fromValue(value);
        }

        public static FinancialRetrievalMode fromValue(String value) {
            if (value == null) {
                return DEFAULT;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "parent-child", "parent_child", "parentchild" -> PARENT_CHILD;
                default -> DEFAULT;
            };
        }
    }

    private static final class FinancialChunk {
        private final String chunkId;
        private final String sourceFile;
        private final String chunkType;
        private final String content;
        private final Map<String, Object> metadata;
        private final double score;
        private int vectorRank;
        private int bm25Rank;
        private double vectorScore;
        private double bm25Score;
        private double hybridScore;
        private double rerankScore;
        private double finalScore;
        private final Set<String> matchedTaskIds = new LinkedHashSet<>();

        private FinancialChunk(String chunkId,
                               String sourceFile,
                               String chunkType,
                               String content,
                               Map<String, Object> metadata,
                               double score) {
            this.chunkId = chunkId;
            this.sourceFile = sourceFile;
            this.chunkType = chunkType;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
        }

        private FinancialChunk copy() {
            FinancialChunk copy = new FinancialChunk(chunkId, sourceFile, chunkType, content, metadata, score);
            copy.vectorRank = vectorRank;
            copy.bm25Rank = bm25Rank;
            copy.vectorScore = vectorScore;
            copy.bm25Score = bm25Score;
            copy.hybridScore = hybridScore;
            copy.rerankScore = rerankScore;
            copy.finalScore = finalScore;
            copy.matchedTaskIds.addAll(matchedTaskIds);
            return copy;
        }

        private FinancialChunk withMatchedTask(String taskId) {
            FinancialChunk copy = copy();
            if (taskId != null && !taskId.isBlank()) {
                copy.matchedTaskIds.add(taskId);
            }
            return copy;
        }

        private String chunkId() {
            return chunkId;
        }

        private String sourceFile() {
            return sourceFile;
        }

        private String chunkType() {
            return chunkType;
        }

        private String content() {
            return content;
        }

        private Map<String, Object> metadata() {
            return metadata;
        }

        private double score() {
            return score;
        }

        private String metadataText(String key) {
            Object value = metadata.get(key);
            return value == null ? "" : String.valueOf(value);
        }

        private double finalScore() {
            if (finalScore > 0) {
                return finalScore;
            }
            if (rerankScore > 0) {
                return rerankScore;
            }
            if (hybridScore > 0) {
                return hybridScore;
            }
            return score;
        }
    }
}
