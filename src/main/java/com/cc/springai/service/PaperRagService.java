package com.cc.springai.service;

import com.cc.springai.agent.ModelRuntimeOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PaperRagService {

    private static final Logger log = LoggerFactory.getLogger(PaperRagService.class);

    private static final Pattern SAFE_TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() { };
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*|\\d+(?:,\\d{3})*(?:\\.\\d+)?%?");
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it",
            "of", "on", "or", "paper", "study", "that", "the", "this", "to", "was", "were", "what",
            "which", "with", "论文", "什么", "多少", "如何"
    );
    private static final int QUERY_COUNT = 4;

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChatClient paperChatClient;
    private final ChatMemory chatMemory;
    private final ModelConfigService modelConfigService;

    @Value("${app.paper-rag.table:qasper_paper_chunks}")
    private String tableName;

    @Value("${app.paper-rag.top-k:8}")
    private int topK;

    @Value("${app.paper-rag.hybrid-top-k:20}")
    private int hybridTopK;

    @Value("${app.paper-rag.judge-model-id:gpt}")
    private String defaultJudgeModelId;

    @Value("${app.paper-rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${app.paper-rag.rerank.url:http://127.0.0.1:8010}")
    private String rerankUrl;

    @Value("${app.paper-rag.rerank.model:Qwen3-Reranker-0.6B}")
    private String rerankModel;

    @Value("${app.paper-rag.rerank.doc-chars:3200}")
    private int rerankDocChars;

    @Value("${app.embedding.ollama.model:unknown}")
    private String embeddingModelName;

    @Value("${app.embedding.ollama.dimensions:0}")
    private int embeddingDimensions;

    public PaperRagService(EmbeddingModel embeddingModel,
                           JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper,
                           @Qualifier("paperChatClient") ChatClient paperChatClient,
                           ChatMemory chatMemory,
                           ModelConfigService modelConfigService) {
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.paperChatClient = paperChatClient;
        this.chatMemory = chatMemory;
        this.modelConfigService = modelConfigService;
    }

    public Flux<String> chat(String prompt, String conversationId, String modelId, RetrievalMode retrievalMode) {
        return prepareChat(prompt, conversationId, modelId, null, retrievalMode)
                .content()
                .filter(event -> "token".equals(event.type()))
                .map(ModelStreamEvent::content);
    }

    public PaperAnswerStream prepareChat(String prompt,
                                         String conversationId,
                                         String modelId,
                                         ModelRuntimeOptions runtimeOptions,
                                         RetrievalMode retrievalMode) {
        long planningStartedAt = System.currentTimeMillis();
        if (prompt == null || prompt.isBlank()) {
            return directAnswer("请输入要查询的科研论文问题。", 0L, 0L, "");
        }
        if (!tableExists()) {
            return directAnswer("论文知识库尚未建立。请先运行 QASPER 准备脚本构建 qasper_paper_chunks 索引。", 0L, 0L, "");
        }

        try {
            RetrievalPlan plan = planRetrieval(prompt, conversationId, modelId);
            long planningMs = elapsedMs(planningStartedAt);
            long retrievalStartedAt = System.currentTimeMillis();
            List<PaperChunk> chunks = search(plan, retrievalMode);
            long retrievalMs = elapsedMs(retrievalStartedAt);
            if (chunks.isEmpty()) {
                return directAnswer("未在 QASPER 论文知识库中找到足够相关的证据。请补充论文标题、方法名或实验指标。",
                        planningMs, retrievalMs, plan.displayQuery());
            }

            String userPrompt = """
                    用户问题：
                    %s

                    论文检索上下文：
                    %s

                    检索规划：
                    - 完整问题：%s
                    - 问题类型：%s

                    请只依据上面的论文上下文回答：
                    1. 先给出简洁、明确的结论。
                    2. 涉及数字时，说明数字对应的模型、数据集、指标和实验条件；需要计算时给出公式。
                    3. 不得把不同论文、章节或实验设置中的数字混在一起。
                    4. 如果证据不足，明确回答“无法从当前论文证据确定”。
                    5. 最后列出证据，格式为 `[序号] 论文标题 — 章节：原文摘录`。
                    """.formatted(prompt, retrievedContext(chunks, retrievalMode), plan.resolvedQuestion(), plan.intent());

            Flux<ModelStreamEvent> content = chatClient(modelId).prompt()
                    .user(userPrompt)
                    .options(modelConfigService.chatOptions(modelId, runtimeOptions))
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .chatResponse()
                    .flatMapIterable(this::eventsFromResponse);
            return new PaperAnswerStream(content, planningMs, retrievalMs, true, plan.displayQuery());
        } catch (Exception ex) {
            return directAnswer("论文问答检索失败：" + ex.getMessage(), elapsedMs(planningStartedAt), 0L, "");
        }
    }

    public EvaluationResult evaluate(EvaluationQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("评测请求不能为空");
        }
        String question = clean(query.question());
        String paperId = clean(query.paperId());
        if (question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (paperId.isBlank()) {
            throw new IllegalArgumentException("paperId 不能为空，QASPER 评测必须按论文精确过滤");
        }
        if (!tableExists()) {
            throw new IllegalStateException("论文知识库尚未建立");
        }

        long startedAt = System.currentTimeMillis();
        int limit = Math.max(1, Math.min(query.topK() <= 0 ? topK : query.topK(), 50));
        EvaluationRetrievalMode mode = query.retrievalMode() == null
                ? EvaluationRetrievalMode.HYBRID_RERANK
                : query.retrievalMode();
        RetrievalMode contextMode = query.contextMode() == null ? RetrievalMode.PARENT_CHILD : query.contextMode();

        long retrievalStartedAt = System.currentTimeMillis();
        EvaluationSearch search = searchForEvaluation(question, paperId, mode, contextMode, limit);
        long retrievalMs = elapsedMs(retrievalStartedAt);

        long generationMs = 0L;
        String answer = "";
        if (query.generateAnswer()) {
            long generationStartedAt = System.currentTimeMillis();
            answer = search.chunks().isEmpty()
                    ? "无法从当前论文证据确定。"
                    : generateEvaluationAnswer(question, paperId, search.chunks(), contextMode, query.modelId());
            generationMs = elapsedMs(generationStartedAt);
        }

        FaithfulnessResult faithfulness = null;
        AnswerEvaluation answerEvaluation = null;
        long judgeMs = 0L;
        if (query.judgeFaithfulness() && !answer.isBlank() && !search.chunks().isEmpty()) {
            long judgeStartedAt = System.currentTimeMillis();
            String judgeModelId = clean(query.judgeModelId()).isBlank()
                    ? defaultJudgeModelId
                    : clean(query.judgeModelId());
            EvaluationJudgement judgement = judgeEvaluation(
                    question,
                    answer,
                    query.goldAnswers(),
                    search.chunks(),
                    contextMode,
                    judgeModelId);
            faithfulness = judgement.faithfulness();
            answerEvaluation = judgement.answerEvaluation();
            judgeMs = elapsedMs(judgeStartedAt);
        }

        List<EvaluationChunk> chunks = new ArrayList<>();
        for (int i = 0; i < search.chunks().size(); i++) {
            PaperChunk chunk = search.chunks().get(i);
            chunks.add(new EvaluationChunk(
                    i + 1,
                    chunk.chunkId(),
                    chunk.paperId(),
                    chunk.title(),
                    chunk.sectionName(),
                    retrievalContent(chunk, contextMode),
                    chunk.finalScore(),
                    chunk.vectorRank,
                    chunk.textRank,
                    chunk.vectorScore,
                    chunk.textScore,
                    chunk.hybridScore,
                    chunk.rerankScore));
        }
        return new EvaluationResult(
                query.questionId(),
                paperId,
                question,
                answer,
                mode.value,
                contextMode == RetrievalMode.PARENT_CHILD ? "parent-child" : "child",
                search.rerankerApplied(),
                chunks,
                chunks.stream().map(EvaluationChunk::content).toList(),
                retrievalMs,
                generationMs,
                judgeMs,
                elapsedMs(startedAt),
                faithfulness,
                answerEvaluation,
                evaluationConfig());
    }

    public EvaluationConfig evaluationConfig() {
        ModelConfigService.ResolvedModelConfig judge = modelConfigService.resolve(defaultJudgeModelId);
        Map<String, Object> rerankerRuntime = rerankerRuntime();
        return new EvaluationConfig(
                tableName,
                topK,
                hybridTopK,
                rerankEnabled,
                rerankUrl,
                rerankModel,
                rerankDocChars,
                rerankerRuntime,
                embeddingModelName,
                embeddingDimensions,
                judge.id(),
                judge.model());
    }

    private PaperAnswerStream directAnswer(String text, long planningMs, long retrievalMs, String query) {
        return new PaperAnswerStream(Flux.just(new ModelStreamEvent("token", text)), planningMs, retrievalMs, false, query);
    }

    private RetrievalPlan planRetrieval(String prompt, String conversationId, String modelId) {
        try {
            String planned = ChatClient.builder(modelConfigService.chatModel(modelId))
                    .defaultSystem("""
                            You plan retrieval for a scientific-paper RAG system built from QASPER papers.
                            Translate Chinese to concise academic English, resolve omitted context from recent turns,
                            preserve model names, dataset names, metrics, numbers and units, and generate retrieval queries.
                            Output strict JSON only and never answer the question.
                            """)
                    .build()
                    .prompt()
                    .user("""
                            Recent conversation:
                            %s

                            Current question:
                            %s

                            Return exactly this schema:
                            {
                              "translatedQuestion": "concise academic English",
                              "resolvedQuestion": "self-contained question",
                              "paperTitle": "paper title only when explicitly stated or safely resolved, otherwise empty",
                              "intent": "one of numeric_result, method, dataset, comparison, evidence, limitation, unanswerable, unknown",
                              "retrievalQueries": ["query 1", "query 2", "query 3", "query 4"]
                            }
                            """.formatted(recentConversationContext(conversationId), prompt))
                    .options(modelConfigService.chatOptions(modelId))
                    .call()
                    .content();
            return parseRetrievalPlan(prompt, planned);
        } catch (Exception ignored) {
            return fallbackPlan(prompt);
        }
    }

    RetrievalPlan parseRetrievalPlan(String prompt, String planned) {
        RetrievalPlan fallback = fallbackPlan(prompt);
        if (planned == null || planned.isBlank()) {
            return fallback;
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(planned));
            String translated = clean(root.path("translatedQuestion").asText(""));
            String resolved = clean(root.path("resolvedQuestion").asText(""));
            String paperTitle = clean(root.path("paperTitle").asText(""));
            String intent = clean(root.path("intent").asText("unknown"));
            List<String> queries = new ArrayList<>();
            addQuery(queries, translated);
            addQuery(queries, resolved);
            JsonNode nodes = root.path("retrievalQueries");
            if (nodes.isArray()) {
                nodes.forEach(node -> addQuery(queries, node.asText("")));
            }
            fallback.queries().forEach(query -> addQuery(queries, query));
            return new RetrievalPlan(
                    translated.isBlank() ? fallback.translatedQuestion() : translated,
                    resolved.isBlank() ? fallback.resolvedQuestion() : resolved,
                    paperTitle,
                    intent.isBlank() ? "unknown" : intent,
                    normalizeQueryCount(queries));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private RetrievalPlan fallbackPlan(String prompt) {
        String question = clean(prompt);
        List<String> queries = new ArrayList<>();
        addQuery(queries, question);
        addQuery(queries, question + " experimental results table metric dataset");
        addQuery(queries, question + " method evaluation evidence");
        addQuery(queries, question + " ablation baseline comparison");
        return new RetrievalPlan(question, question, "", inferIntent(question), normalizeQueryCount(queries));
    }

    private List<PaperChunk> search(RetrievalPlan plan, RetrievalMode retrievalMode) {
        Map<String, PaperChunk> merged = new LinkedHashMap<>();
        for (String query : plan.queries()) {
            for (PaperChunk chunk : searchOne(query, plan.paperTitle(), retrievalMode)) {
                PaperChunk previous = merged.get(chunk.chunkId());
                if (previous == null || chunk.finalScore() > previous.finalScore()) {
                    merged.put(chunk.chunkId(), chunk);
                }
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(PaperChunk::finalScore).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private List<PaperChunk> searchOne(String query, String paperTitle, RetrievalMode retrievalMode) {
        int candidateLimit = Math.max(topK, hybridTopK);
        List<PaperChunk> vectorRows = vectorCandidates(query, paperTitle, candidateLimit * 3);
        List<PaperChunk> textRows = textCandidates(query, paperTitle, candidateLimit * 3);
        List<PaperChunk> ranked = reciprocalRankFusion(vectorRows, textRows, candidateLimit);
        if (retrievalMode == RetrievalMode.PARENT_CHILD) {
            ranked = collapseParentSections(ranked, candidateLimit);
        }
        if (rerankEnabled) {
            ranked = rerank(query, ranked, Math.max(1, topK), retrievalMode);
        }
        return ranked.stream().limit(Math.max(1, topK)).toList();
    }

    private EvaluationSearch searchForEvaluation(String question,
                                                  String paperId,
                                                  EvaluationRetrievalMode mode,
                                                  RetrievalMode contextMode,
                                                  int limit) {
        int candidateLimit = Math.max(limit, hybridTopK);
        List<PaperChunk> vectorRows = mode == EvaluationRetrievalMode.BM25
                ? List.of()
                : vectorCandidatesForPaper(question, paperId, candidateLimit * 3);
        List<PaperChunk> textRows = mode == EvaluationRetrievalMode.VECTOR
                ? List.of()
                : textCandidatesForPaper(question, paperId, candidateLimit * 3);

        List<PaperChunk> ranked = switch (mode) {
            case VECTOR -> vectorRows;
            case BM25 -> textRows;
            case HYBRID, HYBRID_RERANK -> reciprocalRankFusion(vectorRows, textRows, candidateLimit);
        };
        if (contextMode == RetrievalMode.PARENT_CHILD) {
            ranked = collapseParentSections(ranked, candidateLimit);
        }
        boolean rerankerApplied = false;
        if (mode == EvaluationRetrievalMode.HYBRID_RERANK) {
            RerankOutcome outcome = rerankOutcome(question, ranked, limit, contextMode);
            ranked = outcome.chunks();
            rerankerApplied = outcome.applied();
        }
        return new EvaluationSearch(ranked.stream().limit(limit).toList(), rerankerApplied);
    }

    private List<PaperChunk> vectorCandidatesForPaper(String query, String paperId, int limit) {
        float[] embedding = embeddingModel.embed(query);
        String sql = """
                SELECT chunk_id, paper_id, title, section_name, content, metadata::text AS metadata,
                       1 - (embedding <=> ?::vector) AS score
                FROM %s
                WHERE paper_id = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(safeTableName());
        List<PaperChunk> rows = jdbcTemplate.query(sql, this::mapChunk,
                vectorLiteral(embedding), paperId, vectorLiteral(embedding), limit);
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).vectorRank = i + 1;
            rows.get(i).vectorScore = rows.get(i).score();
        }
        return rows;
    }

    private List<PaperChunk> textCandidatesForPaper(String query, String paperId, int limit) {
        try {
            String sql = """
                    SELECT chunk_id, paper_id, title, section_name, content, metadata::text AS metadata,
                           ts_rank_cd(
                               to_tsvector('english', coalesce(title, '') || ' ' || coalesce(section_name, '') || ' ' || content),
                               websearch_to_tsquery('english', ?)
                           ) AS score
                    FROM %s
                    WHERE paper_id = ?
                      AND to_tsvector('english', coalesce(title, '') || ' ' || coalesce(section_name, '') || ' ' || content)
                          @@ websearch_to_tsquery('english', ?)
                    ORDER BY score DESC
                    LIMIT ?
                    """.formatted(safeTableName());
            List<PaperChunk> rows = jdbcTemplate.query(sql, this::mapChunk, query, paperId, query, limit);
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).textRank = i + 1;
                rows.get(i).textScore = rows.get(i).score();
            }
            return rows;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<PaperChunk> vectorCandidates(String query, String paperTitle, int limit) {
        float[] embedding = embeddingModel.embed(query);
        String titleSql = paperTitle.isBlank() ? "" : "WHERE lower(title) LIKE lower(?)";
        List<Object> params = new ArrayList<>();
        params.add(vectorLiteral(embedding));
        if (!paperTitle.isBlank()) {
            params.add("%" + paperTitle + "%");
        }
        params.add(vectorLiteral(embedding));
        params.add(limit);
        String sql = """
                SELECT chunk_id, paper_id, title, section_name, content, metadata::text AS metadata,
                       1 - (embedding <=> ?::vector) AS score
                FROM %s
                %s
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(safeTableName(), titleSql);
        List<PaperChunk> rows = jdbcTemplate.query(sql, this::mapChunk, params.toArray());
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).vectorRank = i + 1;
            rows.get(i).vectorScore = rows.get(i).score();
        }
        return rows;
    }

    private List<PaperChunk> textCandidates(String query, String paperTitle, int limit) {
        try {
            String titleSql = paperTitle.isBlank() ? "" : "AND lower(title) LIKE lower(?)";
            List<Object> params = new ArrayList<>();
            params.add(query);
            params.add(query);
            if (!paperTitle.isBlank()) {
                params.add("%" + paperTitle + "%");
            }
            params.add(limit);
            String sql = """
                    SELECT chunk_id, paper_id, title, section_name, content, metadata::text AS metadata,
                           ts_rank_cd(
                               to_tsvector('english', coalesce(title, '') || ' ' || coalesce(section_name, '') || ' ' || content),
                               websearch_to_tsquery('english', ?)
                           ) AS score
                    FROM %s
                    WHERE to_tsvector('english', coalesce(title, '') || ' ' || coalesce(section_name, '') || ' ' || content)
                          @@ websearch_to_tsquery('english', ?)
                    %s
                    ORDER BY score DESC
                    LIMIT ?
                    """.formatted(safeTableName(), titleSql);
            List<PaperChunk> rows = jdbcTemplate.query(sql, this::mapChunk, params.toArray());
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).textRank = i + 1;
                rows.get(i).textScore = rows.get(i).score();
            }
            return rows;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<PaperChunk> reciprocalRankFusion(List<PaperChunk> vectorRows, List<PaperChunk> textRows, int limit) {
        Map<String, PaperChunk> chunks = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        addRanks(chunks, scores, vectorRows, true);
        addRanks(chunks, scores, textRows, false);
        chunks.forEach((id, chunk) -> {
            chunk.hybridScore = scores.getOrDefault(id, 0.0);
            chunk.finalScore = chunk.hybridScore;
        });
        return chunks.values().stream()
                .sorted(Comparator.comparingDouble(PaperChunk::finalScore).reversed())
                .limit(limit)
                .toList();
    }

    private void addRanks(Map<String, PaperChunk> chunks,
                          Map<String, Double> scores,
                          List<PaperChunk> rows,
                          boolean vector) {
        for (int i = 0; i < rows.size(); i++) {
            PaperChunk source = rows.get(i);
            PaperChunk target = chunks.computeIfAbsent(source.chunkId(), ignored -> source.copy());
            if (vector) {
                target.vectorRank = i + 1;
                target.vectorScore = source.score();
            } else {
                target.textRank = i + 1;
                target.textScore = source.score();
            }
            scores.put(source.chunkId(), scores.getOrDefault(source.chunkId(), 0.0) + 1.0 / (60.0 + i + 1));
        }
    }

    private List<PaperChunk> collapseParentSections(List<PaperChunk> rows, int limit) {
        Map<String, PaperChunk> unique = new LinkedHashMap<>();
        for (PaperChunk row : rows) {
            String key = row.paperId() + "::" + row.sectionName();
            unique.putIfAbsent(key, row);
            if (unique.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private List<PaperChunk> rerank(String query, List<PaperChunk> rows, int limit, RetrievalMode mode) {
        return rerankOutcome(query, rows, limit, mode).chunks();
    }

    private RerankOutcome rerankOutcome(String query, List<PaperChunk> rows, int limit, RetrievalMode mode) {
        if (rows.isEmpty()) {
            return new RerankOutcome(rows, false);
        }
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(trimTrailingSlash(rerankUrl))
                    // Force plain HTTP/1.1. Apache HttpClient may attempt an h2c upgrade,
                    // which Uvicorn does not support and can leave the JSON body unread.
                    .requestFactory(new SimpleClientHttpRequestFactory())
                    .build();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("documents", rows.stream().map(row -> rerankText(row, mode)).toList());
            body.put("top_n", limit);
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
            if (response == null || !response.path("results").isArray()) {
                return new RerankOutcome(rows, false);
            }
            List<PaperChunk> ranked = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (JsonNode result : response.path("results")) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= rows.size() || !seen.add(index)) {
                    continue;
                }
                PaperChunk chunk = rows.get(index).copy();
                chunk.rerankScore = result.path("relevance_score").asDouble(0.0);
                chunk.finalScore = chunk.rerankScore;
                ranked.add(chunk);
            }
            return ranked.isEmpty()
                    ? new RerankOutcome(rows, false)
                    : new RerankOutcome(ranked, true);
        } catch (Exception exception) {
            log.warn("Paper reranker request failed: {}", exception.getMessage());
            return new RerankOutcome(rows, false);
        }
    }

    private String generateEvaluationAnswer(String question,
                                            String paperId,
                                            List<PaperChunk> chunks,
                                            RetrievalMode contextMode,
                                            String modelId) {
        return evaluationChatClient(modelId)
                .prompt()
                .user("""
                        QASPER paper_id: %s

                        Question:
                        %s

                        Retrieved evidence:
                        %s

                        Answer only from the retrieved evidence. Preserve exact numbers, units, model names and dataset names.
                        If the evidence is insufficient, answer exactly: cannot determine from the provided evidence.
                        Keep the answer concise and cite supporting chunk numbers such as [1] and [2].
                        """.formatted(paperId, question, retrievedContext(chunks, contextMode)))
                .options(modelConfigService.chatOptions(modelId))
                .call()
                .content();
    }

    private EvaluationJudgement judgeEvaluation(String question,
                                                 String answer,
                                                 List<String> goldAnswers,
                                                 List<PaperChunk> chunks,
                                                 RetrievalMode contextMode,
                                                 String judgeModelId) {
        try {
            String goldJson = objectMapper.writeValueAsString(goldAnswers == null ? List.of() : goldAnswers);
            String judged = evaluationChatClient(judgeModelId)
                    .prompt()
                    .user("""
                            Question:
                            %s

                            Gold answers:
                            %s

                            Answer to judge:
                            %s

                            Evidence:
                            %s

                            Perform two independent checks:
                            1. Compare the answer with any gold answer for semantic correctness. Preserve distinctions in
                               numbers, units, negation, datasets, methods and experimental conclusions.
                            2. Split the answer into minimal verifiable factual claims. For every claim, decide whether it
                               is directly supported by the numbered evidence. Do not treat a citation marker alone as support.

                            Return strict JSON only with this schema:
                            {
                              "answer_correct": true,
                              "answer_score": 0.0,
                              "answer_reason": "brief semantic comparison",
                              "claims": [
                                {
                                  "claim": "one atomic factual claim",
                                  "supported": true,
                                  "evidence": [1, 2],
                                  "reason": "brief support or contradiction explanation"
                                }
                              ],
                              "faithfulness_reason": "brief overall explanation"
                            }
                            answer_score must be between 0 and 1. If Gold answers is empty, return null for
                            answer_correct and answer_score. Include every factual claim, including numerical claims.
                            """.formatted(question, goldJson, answer, retrievedContext(chunks, contextMode)))
                    .options(modelConfigService.chatOptions(judgeModelId))
                    .call()
                    .content();
            JsonNode root = objectMapper.readTree(extractJson(judged));
            List<ClaimAssessment> claims = new ArrayList<>();
            JsonNode claimNodes = root.path("claims");
            if (claimNodes.isArray()) {
                for (JsonNode node : claimNodes) {
                    String claim = clean(node.path("claim").asText(""));
                    if (claim.isBlank()) continue;
                    List<Integer> evidence = new ArrayList<>();
                    JsonNode evidenceNodes = node.path("evidence");
                    if (evidenceNodes.isArray()) {
                        evidenceNodes.forEach(value -> {
                            int index = value.asInt(-1);
                            if (index > 0 && index <= chunks.size() && !evidence.contains(index)) {
                                evidence.add(index);
                            }
                        });
                    }
                    claims.add(new ClaimAssessment(
                            claim,
                            node.path("supported").asBoolean(false),
                            evidence,
                            clean(node.path("reason").asText(""))));
                }
            }
            int supportedClaims = (int) claims.stream().filter(ClaimAssessment::supported).count();
            int totalClaims = claims.size();
            Double faithfulnessScore = totalClaims == 0 ? null : supportedClaims / (double) totalClaims;
            Boolean answerCorrect = root.hasNonNull("answer_correct")
                    ? root.path("answer_correct").asBoolean(false)
                    : null;
            Double answerScore = root.hasNonNull("answer_score")
                    ? Math.max(0.0, Math.min(1.0, root.path("answer_score").asDouble(0.0)))
                    : null;
            return new EvaluationJudgement(
                    new FaithfulnessResult(
                            faithfulnessScore,
                            supportedClaims,
                            totalClaims,
                            claims,
                            clean(root.path("faithfulness_reason").asText(""))),
                    new AnswerEvaluation(
                            answerCorrect,
                            answerScore,
                            clean(root.path("answer_reason").asText(""))));
        } catch (Exception ex) {
            String reason = "judge failed: " + clean(ex.getMessage());
            return new EvaluationJudgement(
                    new FaithfulnessResult(null, 0, 0, List.of(), reason),
                    new AnswerEvaluation(null, null, reason));
        }
    }

    private ChatClient evaluationChatClient(String modelId) {
        return ChatClient.builder(modelConfigService.chatModel(modelId))
                .defaultSystem("""
                        You are an evaluation-only scientific paper QA assistant.
                        Use only the supplied QASPER evidence. Never use conversation memory or outside knowledge.
                        """)
                .build();
    }

    private String retrievedContext(List<PaperChunk> chunks, RetrievalMode mode) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            PaperChunk chunk = chunks.get(i);
            context.append('[').append(i + 1).append("] paper=").append(chunk.title())
                    .append(" | paper_id=").append(chunk.paperId())
                    .append(" | section=").append(chunk.sectionName())
                    .append(" | score=").append(String.format(Locale.ROOT, "%.4f", chunk.finalScore()))
                    .append('\n').append(retrievalContent(chunk, mode)).append("\n\n");
        }
        return context.toString().strip();
    }

    private String rerankText(PaperChunk chunk, RetrievalMode mode) {
        String value = "Paper: " + chunk.title() + "\nSection: " + chunk.sectionName() + "\n" + retrievalContent(chunk, mode);
        return rerankDocChars > 0 && value.length() > rerankDocChars
                ? value.substring(0, rerankDocChars).stripTrailing()
                : value;
    }

    private String retrievalContent(PaperChunk chunk, RetrievalMode mode) {
        if (mode == RetrievalMode.PARENT_CHILD) {
            String parent = chunk.metadataText("parent_context");
            if (!parent.isBlank()) {
                return parent;
            }
        }
        return chunk.content();
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
        Object reasoning = output.getMetadata() == null ? null : output.getMetadata().get("reasoningContent");
        if (reasoning == null && output.getMetadata() != null) {
            reasoning = output.getMetadata().get("reasoning_content");
        }
        if (reasoning != null && !String.valueOf(reasoning).isBlank()) {
            events.add(new ModelStreamEvent("reasoning", String.valueOf(reasoning)));
        }
        if (output.getText() != null && !output.getText().isEmpty()) {
            events.add(new ModelStreamEvent("token", output.getText()));
        }
        return events;
    }

    private ChatClient chatClient(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return paperChatClient;
        }
        return ChatClient.builder(modelConfigService.chatModel(modelId))
                .defaultSystem("""
                        你是科研论文 RAG 问答助手，只能依据提供的论文上下文回答。
                        精确保留实验数字、单位、模型、数据集和实验条件；证据不足时拒绝推测；结尾列出引用证据。
                        """)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    private boolean tableExists() {
        try {
            Boolean exists = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name = ?
                    )
                    """, Boolean.class, safeTableName());
            return Boolean.TRUE.equals(exists);
        } catch (Exception ignored) {
            return false;
        }
    }

    private PaperChunk mapChunk(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Map<String, Object> metadata = Map.of();
        try {
            JsonNode node = objectMapper.readTree(rs.getString("metadata"));
            metadata = objectMapper.convertValue(node, METADATA_TYPE);
        } catch (Exception ignored) {
        }
        return new PaperChunk(rs.getString("chunk_id"), rs.getString("paper_id"), rs.getString("title"),
                rs.getString("section_name"), rs.getString("content"), metadata, rs.getDouble("score"));
    }

    private String recentConversationContext(String conversationId) {
        try {
            List<Message> messages = chatMemory.get(conversationId);
            if (messages == null || messages.isEmpty()) {
                return "(none)";
            }
            int start = Math.max(0, messages.size() - 6);
            StringBuilder context = new StringBuilder();
            for (Message message : messages.subList(start, messages.size())) {
                String text = clean(message.getText());
                if (!text.isBlank()) {
                    context.append(message.getMessageType().name().toLowerCase(Locale.ROOT))
                            .append(": ").append(text, 0, Math.min(500, text.length())).append('\n');
                }
            }
            return context.isEmpty() ? "(none)" : context.toString().strip();
        } catch (Exception ignored) {
            return "(none)";
        }
    }

    private String inferIntent(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(how many|how much|size|count|number|accuracy|f1|bleu|rouge|percent|percentage|多少|规模|数量|准确率|指标).*")) {
            return "numeric_result";
        }
        if (lower.matches(".*(compare|versus|baseline|improve|相比|提升|基线).*")) {
            return "comparison";
        }
        if (lower.matches(".*(dataset|corpus|sample|数据集|样本).*")) {
            return "dataset";
        }
        if (lower.matches(".*(limitation|weakness|局限|不足).*")) {
            return "limitation";
        }
        return "method";
    }

    private List<String> normalizeQueryCount(List<String> queries) {
        List<String> result = new ArrayList<>();
        queries.forEach(query -> addQuery(result, query));
        String seed = result.isEmpty() ? "scientific paper experiment" : result.get(0);
        addQuery(result, seed + " results evaluation metric");
        addQuery(result, seed + " method dataset baseline");
        addQuery(result, seed + " table ablation analysis");
        addQuery(result, seed + " conclusion evidence");
        return List.copyOf(result.subList(0, Math.min(QUERY_COUNT, result.size())));
    }

    private void addQuery(List<String> queries, String query) {
        String normalized = clean(query);
        if (!normalized.isBlank() && queries.stream().noneMatch(value -> value.equalsIgnoreCase(normalized))) {
            queries.add(normalized);
        }
    }

    List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN_PATTERN.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String extractJson(String value) {
        String text = value.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private String vectorLiteral(float[] values) {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                vector.append(',');
            }
            vector.append(values[i]);
        }
        return vector.append(']').toString();
    }

    private String safeTableName() {
        String value = tableName == null || tableName.isBlank() ? "qasper_paper_chunks" : tableName;
        if (!SAFE_TABLE.matcher(value).matches()) {
            throw new IllegalArgumentException("非法论文索引表名");
        }
        return value;
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private Map<String, Object> rerankerRuntime() {
        if (!rerankEnabled || rerankUrl == null || rerankUrl.isBlank()) {
            return Map.of("status", "disabled");
        }
        try {
            JsonNode health = RestClient.builder()
                    .baseUrl(trimTrailingSlash(rerankUrl))
                    .requestFactory(new SimpleClientHttpRequestFactory())
                    .build()
                    .get()
                    .uri("/health")
                    .retrieve()
                    .body(JsonNode.class);
            if (health == null || !health.isObject()) {
                return Map.of("status", "unavailable");
            }
            return objectMapper.convertValue(health, METADATA_TYPE);
        } catch (Exception exception) {
            return Map.of("status", "unavailable", "error", clean(exception.getMessage()));
        }
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    record RetrievalPlan(String translatedQuestion,
                         String resolvedQuestion,
                         String paperTitle,
                         String intent,
                         List<String> queries) {
        String displayQuery() {
            return String.join(" | ", queries);
        }
    }

    public record ModelStreamEvent(String type, String content) {
    }

    public record PaperAnswerStream(Flux<ModelStreamEvent> content,
                                    long translationMs,
                                    long retrievalMs,
                                    boolean modelRequested,
                                    String retrievalQuery) {
    }

    public record EvaluationQuery(String questionId,
                                  String paperId,
                                  String question,
                                  EvaluationRetrievalMode retrievalMode,
                                  RetrievalMode contextMode,
                                  int topK,
                                  String modelId,
                                  String judgeModelId,
                                  List<String> goldAnswers,
                                  boolean generateAnswer,
                                  boolean judgeFaithfulness) {
    }

    public record EvaluationChunk(int rank,
                                  String chunkId,
                                  String paperId,
                                  String title,
                                  String sectionName,
                                  String content,
                                  double score,
                                  int vectorRank,
                                  int textRank,
                                  double vectorScore,
                                  double textScore,
                                  double hybridScore,
                                  double rerankScore) {
    }

    public record ClaimAssessment(String claim,
                                  boolean supported,
                                  List<Integer> evidence,
                                  String reason) {
    }

    public record FaithfulnessResult(Double score,
                                     int supportedClaims,
                                     int totalClaims,
                                     List<ClaimAssessment> claims,
                                     String reason) {
    }

    public record AnswerEvaluation(Boolean correct, Double score, String reason) {
    }

    private record EvaluationJudgement(FaithfulnessResult faithfulness,
                                       AnswerEvaluation answerEvaluation) {
    }

    public record EvaluationConfig(String table,
                                   int topK,
                                   int hybridTopK,
                                   boolean rerankerEnabled,
                                   String rerankerUrl,
                                   String rerankerModel,
                                   int rerankerDocChars,
                                   Map<String, Object> rerankerRuntime,
                                   String embeddingModel,
                                   int embeddingDimensions,
                                   String defaultJudgeModelId,
                                   String defaultJudgeModel) {
    }

    public record EvaluationResult(String questionId,
                                   String paperId,
                                   String question,
                                   String answer,
                                   String retrievalMode,
                                   String contextMode,
                                   boolean rerankerApplied,
                                   List<EvaluationChunk> retrievedChunks,
                                   List<String> citations,
                                   long retrievalMs,
                                   long generationMs,
                                   long judgeMs,
                                   long latencyMs,
                                   FaithfulnessResult faithfulness,
                                   AnswerEvaluation answerEvaluation,
                                   EvaluationConfig evaluationConfig) {
    }

    public enum EvaluationRetrievalMode {
        VECTOR("vector"),
        BM25("bm25"),
        HYBRID("hybrid"),
        HYBRID_RERANK("hybrid_rerank");

        private final String value;

        EvaluationRetrievalMode(String value) {
            this.value = value;
        }

        public static EvaluationRetrievalMode fromValue(String value) {
            if (value == null || value.isBlank()) {
                return HYBRID_RERANK;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "vector" -> VECTOR;
                case "bm25" -> BM25;
                case "hybrid" -> HYBRID;
                case "hybrid_rerank", "rerank" -> HYBRID_RERANK;
                default -> throw new IllegalArgumentException("retrievalMode 必须是 vector、bm25、hybrid 或 hybrid_rerank");
            };
        }
    }

    public enum RetrievalMode {
        DEFAULT,
        PARENT_CHILD;

        public static RetrievalMode fromValue(String value) {
            if (value == null) {
                return PARENT_CHILD;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "default" -> DEFAULT;
                default -> PARENT_CHILD;
            };
        }
    }

    private static final class PaperChunk {
        private final String chunkId;
        private final String paperId;
        private final String title;
        private final String sectionName;
        private final String content;
        private final Map<String, Object> metadata;
        private final double score;
        private int vectorRank;
        private int textRank;
        private double vectorScore;
        private double textScore;
        private double hybridScore;
        private double rerankScore;
        private double finalScore;

        private PaperChunk(String chunkId, String paperId, String title, String sectionName,
                           String content, Map<String, Object> metadata, double score) {
            this.chunkId = chunkId;
            this.paperId = paperId;
            this.title = title;
            this.sectionName = sectionName;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
        }

        private PaperChunk copy() {
            PaperChunk copy = new PaperChunk(chunkId, paperId, title, sectionName, content, metadata, score);
            copy.vectorRank = vectorRank;
            copy.textRank = textRank;
            copy.vectorScore = vectorScore;
            copy.textScore = textScore;
            copy.hybridScore = hybridScore;
            copy.rerankScore = rerankScore;
            copy.finalScore = finalScore;
            return copy;
        }

        private String chunkId() { return chunkId; }
        private String paperId() { return paperId; }
        private String title() { return title; }
        private String sectionName() { return sectionName; }
        private String content() { return content; }
        private double score() { return score; }
        private String metadataText(String key) {
            Object value = metadata.get(key);
            return value == null ? "" : String.valueOf(value);
        }
        private double finalScore() {
            if (finalScore > 0) return finalScore;
            if (rerankScore > 0) return rerankScore;
            if (hybridScore > 0) return hybridScore;
            return score;
        }
    }

    private record EvaluationSearch(List<PaperChunk> chunks, boolean rerankerApplied) {
    }

    private record RerankOutcome(List<PaperChunk> chunks, boolean applied) {
    }
}
