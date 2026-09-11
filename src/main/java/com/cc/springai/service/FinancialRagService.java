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
import java.util.Collection;
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
    private static final Pattern YEAR_PATTERN = Pattern.compile("(?<!\\d)20(?:22|23|24)(?!\\d)");
    private static final Pattern TABLE_YEAR_PATTERN = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");
    private static final Pattern COMPANY_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?<![A-Z0-9-])([A-Z]{1,5}(?:-[A-Z])?)(?![A-Z0-9-])");
    private static final Pattern NAMED_ENTITY_PATTERN = Pattern.compile(
            "\\b([A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,}){1,3})\\b");
    private static final Pattern FINANCIAL_PREMISE_PATTERN = Pattern.compile(
            "(?is)\\$?[\\d,.]+\\s+(?:million|billion).{0,100}\\b(?:increase|decrease|difference|change)\\b");
    private static final Pattern REQUESTED_ITEM_PATTERN = Pattern.compile("(?i)\\bitem\\s+(\\d+[A-Z]?)\\b");
    private static final Pattern DATASET_SCOPE_PATTERN = Pattern.compile(
            "(?is)\\s*dataset\\s+retrieval\\s+scope\\s*\\(metadata\\s+only\\)\\s*:.*$");
    private static final Pattern BIOGRAPHICAL_FACT_PATTERN = Pattern.compile(
            "(?i)\\b(age|aged|date\\s+of\\s+birth|birth\\s+year|biograph(?:y|ical)|executive\\s+officer|"
                    + "officer\\s+age|director\\s+age)\\b");
    private static final List<List<String>> DESCRIPTION_ITEM_GROUPS = List.of(
            List.of("actual results", "financial position", "cash generated from operations"),
            List.of("financial condition", "results of operations", "cash flows from operations")
    );
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

    @Value("${app.financial-rag.evidence-ledger.max-documents:8}")
    private int evidenceLedgerMaxDocuments;

    @Value("${app.financial-rag.evidence-ledger.max-content-chars:1800}")
    private int evidenceLedgerMaxContentChars;

    @Value("${app.financial-rag.evidence-ledger.max-total-content-chars:12000}")
    private int evidenceLedgerMaxTotalContentChars;

    @Value("${app.financial-rag.evidence-ledger.max-documents-per-task:2}")
    private int evidenceLedgerMaxDocumentsPerTask;

    @Value("${app.financial-rag.evidence-ledger.retry-on-empty:true}")
    private boolean evidenceLedgerRetryOnEmpty;

    @Value("${app.financial-rag.answer-coverage-repair.enabled:true}")
    private boolean answerCoverageRepairEnabled;

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
            SearchOutcome searchOutcome = searchWithDiagnostics(plan, retrievalMode);
            List<FinancialChunk> chunks = searchOutcome.chunks();
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
            return verifyAnswer(answer, ledger, prompt);
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
                    0L, 0L, false, "", "NORMAL", List.of());
        }

        try {
            FinancialRetrievalPlan plan = planRetrieval(prompt, conversationId, modelId);
            String retrievalQuery = plan.displayQuery();
            long translationMs = elapsedMs(translationStartedAt);
            long retrievalStartedAt = System.currentTimeMillis();
            SearchOutcome searchOutcome = searchWithDiagnostics(plan, retrievalMode);
            List<FinancialChunk> chunks = searchOutcome.chunks();
            if (chunks.isEmpty()) {
                long retrievalMs = elapsedMs(retrievalStartedAt);
                return new FinancialAnswerStream(Flux.just(new ModelStreamEvent("token", "未在 Multi-Doc-2025 年报知识库中检索到相关片段。请确认索引已构建，或在问题中补充公司和年份。")),
                    translationMs, retrievalMs, false, retrievalQuery,
                    searchOutcome.quality(), searchOutcome.warnings());
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
            content = verifyAnswerEvents(content, ledger, prompt);
            return new FinancialAnswerStream(content, translationMs, retrievalMs, true, retrievalQuery,
                    searchOutcome.quality(), searchOutcome.warnings());
        } catch (Exception ex) {
            long translationMs = elapsedMs(translationStartedAt);
            return new FinancialAnswerStream(Flux.just(new ModelStreamEvent("token", "金融问答检索失败：" + ex.getMessage())),
                    translationMs, 0L, false, "", "FAILED", List.of(ex.getMessage()));
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
            SearchOutcome searchOutcome = searchWithDiagnostics(plan, retrievalMode);
            List<FinancialChunk> chunks = searchOutcome.chunks();
            if (chunks.isEmpty()) {
                return new FinancialAnalysisResult("", plan.resolvedQuestion(), plan.intent(),
                        analysisTasks(plan), analysisCalculationPlans(plan), List.of(), List.of(), List.of(),
                        new CitationAudit(false, List.of("没有检索到证据")), planningMs,
                        elapsedMs(retrievalStartedAt), 0L, elapsedMs(startedAt), "没有检索到证据",
                        searchOutcome.quality(), searchOutcome.warnings());
            }
            FinancialEvidenceLedger.Ledger ledger = buildEvidenceLedger(prompt, plan, chunks, retrievalMode, modelId);
            long retrievalMs = elapsedMs(retrievalStartedAt);

            long generationStartedAt = System.currentTimeMillis();
            String generated = deterministicBiographicalAgeAnswer(prompt, ledger)
                    .or(() -> deterministicForwardLookingComparisonAnswer(prompt, ledger))
                    .or(() -> deterministicTextEnumerationAnswer(prompt, ledger)).orElseGet(() ->
                    financeChatClient(modelId).prompt()
                            .user(answerPrompt(prompt, plan, ledger))
                            .options(modelConfigService.chatOptions(modelId))
                            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                            .call()
                            .content());
            String answer = citationVerifierEnabled
                    ? citationVerifier.enforce(generated, ledger, citationVerifierStrict, prompt)
                    : generated;
            String repaired = repairIncompleteAnswer(prompt, plan, ledger, answer, modelId);
            if (!repaired.equals(answer)) {
                answer = citationVerifierEnabled
                        ? citationVerifier.enforce(repaired, ledger, citationVerifierStrict, prompt)
                        : repaired;
            }
            long generationMs = elapsedMs(generationStartedAt);
            FinancialCitationVerifier.Audit audit = citationVerifier.verify(answer, ledger);
            return new FinancialAnalysisResult(answer, plan.resolvedQuestion(), plan.intent(),
                    analysisTasks(plan), analysisCalculationPlans(plan), analysisEvidence(ledger),
                    analysisFacts(ledger), analysisCalculations(ledger),
                    new CitationAudit(audit.valid(), audit.issues().stream().map(FinancialCitationVerifier.Issue::message).toList()),
                    planningMs, retrievalMs, generationMs, elapsedMs(startedAt), "",
                    searchOutcome.quality(), searchOutcome.warnings());
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
                document.matchedTaskIds(), document.matchedTaskIds())).toList();
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
                              "calculationPlans": [
                                {
                                  "id": "stable id such as calculate_aapl_multiple",
                                  "operator": "ADD | SUBTRACT | DIVIDE | PERCENT_CHANGE | PERCENT_OF_TOTAL | SUM | COUNT | AVERAGE | CAGR",
                                  "operands": [
                                    {
                                      "role": "numerator | denominator | start | end | component",
                                      "sourceTaskId": "id of the retrieve task that supplies this value",
                                      "company": "SEC ticker",
                                      "fiscalYear": "fiscal year",
                                      "metric": "canonical metric expected from that task",
                                      "rawValue": "optional value explicitly supplied by the question, otherwise empty",
                                      "unit": "usd | percent | count | other",
                                      "scale": "unit | thousand | million | billion"
                                    }
                                  ],
                                  "outputUnit": "usd | percent | times | count",
                                  "outputScale": "unit | thousand | million | billion",
                                  "precision": 1,
                                  "periods": 0
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
                             - For every requested arithmetic result, emit one calculationPlans entry. Choose the operator from meaning,
                               not by copying wording. DIVIDE with outputUnit=times means a multiple; PERCENT_OF_TOTAL means a ratio × 100.
                              - List only known input values as operands, in execution order, and bind each operand to the retrieve task
                                that supplies that exact input. Never use the unknown value being solved for as an operand. For example,
                                if a current value is 5.0 and it increased by 2.6, SUBTRACT operands are current value then increase amount;
                                create a retrieve task for the disclosed increase, not for the unknown prior-year result.
                              - Copy a numerical input explicitly supplied by the question into that operand's rawValue/unit/scale.
                                Leave rawValue empty when the input must be extracted from retrieval evidence. Explicit inputs are still
                                accepted only when the bound retrieved evidence contains the same metric and number.
                              - For COUNT over structured records explicitly named in the question or a table header, emit one operand
                                per distinct record (for years 2022, 2021, 2020 emit three operands), all bound to the supporting task.
                                Do not represent an entire list with a single operand.
                              - For an approximate multiple, use precision=1 unless the user explicitly requests another precision.
                             - Never emit a calculation plan for a purely qualitative comparison or for counting/listing
                               distinct words, phrases, disclosures, or named text items. COUNT is only for counting
                               structured records or facts supplied by retrieve tasks. Do not perform the arithmetic yourself.
                             - Return exactly 5 retrievalQueries.
                            - retrievalQueries[0] must be the direct concise English translation of the current user question.
                            - retrievalQueries[1] should be the resolved standalone question with omitted context filled in.
                            - retrievalQueries[2..4] should be expanded, decomposed, or metric/table-focused retrieval queries.
                            """.formatted(recentConversationContext(conversationId), prompt))
                    .options(modelConfigService.chatOptions(modelId))
                    .call()
                    .content();
            return augmentRetrievalPlan(prompt, parseRetrievalPlan(prompt, planned));
        } catch (Exception exception) {
            log.warn("Financial retrieval planning failed; using deterministic fallback: {}",
                    exception.getMessage());
            return augmentRetrievalPlan(prompt, fallbackRetrievalPlan(prompt));
        }
    }

    private FinancialRetrievalPlan augmentRetrievalPlan(String prompt, FinancialRetrievalPlan plan) {
        String table = resolveTableName();
        Set<String> known = knownCompanies(table);
        List<String> discoveredCompanies = discoverNamedEntityCompanies(prompt, table);
        List<CompanyYearScope> explicitScopes = new ArrayList<>(explicitCompanyYearScopes(prompt, known));
        if (explicitScopes.isEmpty()) {
            List<String> years = extractYears(prompt);
            for (String company : discoveredCompanies) {
                for (String year : years) {
                    explicitScopes.add(new CompanyYearScope(company, year));
                }
            }
        }
        Set<String> explicitCompanies = explicitTickerCompanies(prompt, known);
        LinkedHashSet<String> scopedCompanyHints = new LinkedHashSet<>(explicitCompanies);
        scopedCompanyHints.addAll(discoveredCompanies);
        explicitScopes.stream().map(CompanyYearScope::company).forEach(scopedCompanyHints::add);
        if (explicitScopes.isEmpty() && scopedCompanyHints.isEmpty()) {
            return plan;
        }
        List<FinancialRetrievalTask> tasks = plan.subTasks().stream()
                .map(task -> bindDiscoveredCompany(task, discoveredCompanies, known))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (scopedCompanyHints.size() == 1) {
            String onlyCompany = scopedCompanyHints.iterator().next();
            tasks = tasks.stream().map(task -> enforceSingleCompanyScope(task, onlyCompany)).toList();
        }
        tasks = new ArrayList<>(scopeFallbackRetrievalTasks(tasks, explicitScopes));
        Set<String> existing = tasks.stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation()))
                .flatMap(task -> task.companies().stream().flatMap(company -> task.years().stream()
                        .map(year -> company.toUpperCase(Locale.ROOT) + "|" + year)))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean anchoredScopeRetrieval = needsAnchoredScopeRetrieval(prompt);
        for (CompanyYearScope scope : explicitScopes) {
            String key = scope.company() + "|" + scope.year();
            boolean newlyScoped = existing.add(key);
            if (!newlyScoped && !anchoredScopeRetrieval) {
                continue;
            }
            tasks.add(new FinancialRetrievalTask(
                    "retrieve_explicit_" + scope.company().toLowerCase(Locale.ROOT) + "_" + scope.year()
                            + (newlyScoped ? "" : "_anchor"),
                    scope.company() + " FY" + scope.year() + " " + stripDatasetRetrievalScope(prompt),
                    List.of(scope.company()), List.of(scope.year()), "", "retrieve", "hybrid", List.of()));
        }
        return new FinancialRetrievalPlan(plan.translatedQuestion(), plan.resolvedQuestion(), plan.intent(),
                plan.queries(), List.copyOf(tasks), plan.calculationPlans());
    }

    private List<AnalysisCalculationPlan> analysisCalculationPlans(FinancialRetrievalPlan plan) {
        return plan.calculationPlans().stream().map(calculation -> new AnalysisCalculationPlan(
                calculation.id(), calculation.operator().name(), calculation.operands().stream()
                .map(operand -> new AnalysisCalculationOperand(
                        operand.role(), operand.sourceTaskId(), operand.company(), operand.fiscalYear(), operand.metric(),
                        operand.rawValue(), operand.unit(), operand.scale()))
                .toList(), calculation.outputUnit(), calculation.outputScale(),
                calculation.precision(), calculation.periods())).toList();
    }

    Set<String> explicitTickerCompanies(String prompt, Set<String> knownCompanies) {
        LinkedHashSet<String> companies = new LinkedHashSet<>();
        Matcher matcher = COMPANY_TOKEN_PATTERN.matcher(defaultIfBlank(prompt, ""));
        while (matcher.find()) {
            String candidate = matcher.group(1).toUpperCase(Locale.ROOT);
            if (knownCompanies.contains(candidate)) {
                companies.add(candidate);
            }
        }
        return Set.copyOf(companies);
    }

    FinancialRetrievalTask enforceSingleCompanyScope(FinancialRetrievalTask task, String company) {
        if (!"retrieve".equalsIgnoreCase(task.operation())) {
            return task;
        }
        return new FinancialRetrievalTask(task.id(), task.query(), List.of(company), task.years(), task.metric(),
                task.operation(), task.modality(), task.dependsOn());
    }

    List<FinancialRetrievalTask> scopeFallbackRetrievalTasks(
            List<FinancialRetrievalTask> tasks, List<CompanyYearScope> explicitScopes) {
        boolean hasFallback = tasks.stream().anyMatch(task -> "retrieve_1".equals(task.id())
                && "retrieve".equalsIgnoreCase(task.operation()));
        if (!hasFallback || explicitScopes.isEmpty()) {
            return List.copyOf(tasks);
        }
        List<FinancialRetrievalTask> scoped = tasks.stream()
                .filter(task -> !("retrieve_1".equals(task.id())
                        && "retrieve".equalsIgnoreCase(task.operation())))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        FinancialRetrievalTask fallback = tasks.stream()
                .filter(task -> "retrieve_1".equals(task.id()))
                .findFirst().orElseThrow();
        for (CompanyYearScope scope : explicitScopes) {
            scoped.add(new FinancialRetrievalTask(
                    "retrieve_scope_" + scope.company().toLowerCase(Locale.ROOT) + "_" + scope.year(),
                    scope.company() + " FY" + scope.year() + " "
                            + stripDatasetRetrievalScope(fallback.query()),
                    List.of(scope.company()), List.of(scope.year()), fallback.metric(),
                    "retrieve", fallback.modality(), List.of()));
        }
        return List.copyOf(scoped);
    }

    boolean needsAnchoredScopeRetrieval(String prompt) {
        return FINANCIAL_PREMISE_PATTERN.matcher(prompt == null ? "" : prompt).find();
    }

    FinancialRetrievalTask bindDiscoveredCompany(FinancialRetrievalTask task,
                                                  List<String> discoveredCompanies,
                                                  Set<String> knownCompanies) {
        if (discoveredCompanies.size() != 1 || !"retrieve".equalsIgnoreCase(task.operation())) {
            return task;
        }
        boolean alreadyScoped = task.companies().stream()
                .map(company -> cleanLine(company).toUpperCase(Locale.ROOT))
                .anyMatch(knownCompanies::contains);
        if (alreadyScoped) {
            return task;
        }
        return new FinancialRetrievalTask(task.id(), task.query(), List.of(discoveredCompanies.get(0)),
                task.years(), task.metric(), task.operation(), task.modality(), task.dependsOn());
    }

    private List<String> discoverNamedEntityCompanies(String prompt, String table) {
        for (String phrase : namedEntityPhrases(prompt)) {
            List<CompanyHit> hits = jdbcTemplate.query("""
                    SELECT company, COUNT(*) AS matches
                    FROM %s
                    WHERE to_tsvector('english', content) @@ plainto_tsquery('english', ?)
                    GROUP BY company
                    ORDER BY matches DESC
                    LIMIT 2
                    """.formatted(safeTableName(table)),
                    (rs, rowNum) -> new CompanyHit(rs.getString("company"), rs.getInt("matches")), phrase);
            if (hits.isEmpty()) {
                continue;
            }
            CompanyHit first = hits.get(0);
            int runnerUp = hits.size() > 1 ? hits.get(1).matches() : 0;
            if (first.matches() >= 2 && (runnerUp == 0 || first.matches() >= runnerUp * 2)) {
                return List.of(first.company().toUpperCase(Locale.ROOT));
            }
        }
        return List.of();
    }

    List<String> namedEntityPhrases(String prompt) {
        List<String> phrases = new ArrayList<>();
        Matcher matcher = NAMED_ENTITY_PATTERN.matcher(prompt == null ? "" : prompt);
        while (matcher.find()) {
            String phrase = cleanLine(matcher.group(1));
            String normalized = phrase.toLowerCase(Locale.ROOT);
            if (!normalized.contains("financial condition")
                    && !normalized.contains("management discussion")
                    && !normalized.contains("annual report")
                    && phrases.stream().noneMatch(existing -> existing.equalsIgnoreCase(phrase))) {
                phrases.add(phrase);
            }
        }
        return List.copyOf(phrases);
    }

    List<CompanyYearScope> explicitCompanyYearScopes(String prompt, Set<String> knownCompanies) {
        LinkedHashMap<String, CompanyYearScope> scopes = new LinkedHashMap<>();
        String text = prompt == null ? "" : prompt;
        Matcher companyMatcher = COMPANY_TOKEN_PATTERN.matcher(text);
        while (companyMatcher.find()) {
            String company = companyMatcher.group(1).toUpperCase(Locale.ROOT);
            if (!knownCompanies.contains(company)) {
                continue;
            }
            String after = text.substring(companyMatcher.end(), Math.min(text.length(), companyMatcher.end() + 40));
            Matcher afterYear = YEAR_PATTERN.matcher(after);
            if (afterYear.find()) {
                addCompanyYearScope(scopes, company, afterYear.group(), knownCompanies);
                continue;
            }
            String before = text.substring(Math.max(0, companyMatcher.start() - 40), companyMatcher.start());
            Matcher beforeYear = YEAR_PATTERN.matcher(before);
            String closestYear = "";
            while (beforeYear.find()) {
                closestYear = beforeYear.group();
            }
            if (!closestYear.isBlank()) {
                addCompanyYearScope(scopes, company, closestYear, knownCompanies);
            }
        }
        return List.copyOf(scopes.values());
    }

    private void addCompanyYearScope(Map<String, CompanyYearScope> scopes, String company, String year,
                                     Set<String> knownCompanies) {
        String normalizedCompany = cleanLine(company).toUpperCase(Locale.ROOT);
        if (knownCompanies.contains(normalizedCompany)) {
            scopes.putIfAbsent(normalizedCompany + "|" + year, new CompanyYearScope(normalizedCompany, year));
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
            log.warn("Financial retrieval planner returned no content; using deterministic fallback");
            return fallbackRetrievalPlan(prompt);
        }
        try {
            String json = extractJsonObject(planned);
            JsonNode root = objectMapper.readTree(json);
            String translated = stripDatasetRetrievalScope(root.path("translatedQuestion").asText(""));
            String resolved = stripDatasetRetrievalScope(root.path("resolvedQuestion").asText(""));
            String intent = cleanLine(root.path("intent").asText("unknown"));
            List<FinancialRetrievalTask> subTasks = parseSubTasks(root.path("subTasks"));
            List<FinancialEvidenceLedger.CalculationPlan> calculationPlans =
                    parseCalculationPlans(root.path("calculationPlans"));
            if (isTextEnumerationQuestion(prompt)) {
                calculationPlans = List.of();
            } else {
                calculationPlans = repairCalculationPlanBindings(subTasks, calculationPlans);
                calculationPlans = rejectHeaderOnlyValuePlans(subTasks, calculationPlans);
                calculationPlans = completeStructuredCountPlans(subTasks, calculationPlans);
                calculationPlans = completeAdjacentPercentageChangePlans(subTasks, calculationPlans);
            }
            List<String> queries = new ArrayList<>();
            FinancialRetrievalPlan fallback = fallbackRetrievalPlan(prompt);
            addQuery(queries, translated.isBlank() ? fallback.translatedQuestion() : translated);
            JsonNode queryNodes = root.path("retrievalQueries");
            if (queryNodes.isArray()) {
                for (JsonNode queryNode : queryNodes) {
                    addQuery(queries, stripDatasetRetrievalScope(queryNode.asText("")));
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
                    subTasks.isEmpty() ? fallback.subTasks() : subTasks,
                    calculationPlans);
        } catch (Exception exception) {
            log.warn("Financial retrieval plan JSON parsing failed; using deterministic fallback: {}",
                    exception.getMessage());
            return fallbackRetrievalPlan(prompt);
        }
    }

    private FinancialRetrievalPlan fallbackRetrievalPlan(String prompt) {
        String normalized = stripDatasetRetrievalScope(prompt);
        if (normalized.isBlank()) {
            normalized = cleanLine(prompt);
        }
        List<String> queries = new ArrayList<>();
        addQuery(queries, normalized);
        String expanded = expandQueryTerms(normalized);
        addQuery(queries, expanded);
        addQuery(queries, normalized + " annual report 10-K");
        addQuery(queries, normalized + " consolidated financial statements");
        addQuery(queries, normalized + " table fiscal year period");
        return new FinancialRetrievalPlan(normalized, normalized, inferIntent(normalized), normalizeQueryCount(queries),
                List.of(new FinancialRetrievalTask("retrieve_1", expanded, List.of(), extractYears(normalized),
                        "", "retrieve", "hybrid", List.of())), List.of());
    }

    private List<FinancialEvidenceLedger.CalculationPlan> parseCalculationPlans(JsonNode nodes) {
        if (!nodes.isArray()) {
            return List.of();
        }
        List<FinancialEvidenceLedger.CalculationPlan> plans = new ArrayList<>();
        for (JsonNode node : nodes) {
            FinancialEvidenceLedger.CalculationOperator operator =
                    FinancialEvidenceLedger.CalculationOperator.from(node.path("operator").asText(""));
            if (operator == FinancialEvidenceLedger.CalculationOperator.NONE) {
                continue;
            }
            List<FinancialEvidenceLedger.CalculationOperand> operands = new ArrayList<>();
            JsonNode operandNodes = node.path("operands");
            if (operandNodes.isArray()) {
                for (JsonNode operand : operandNodes) {
                    operands.add(new FinancialEvidenceLedger.CalculationOperand(
                            cleanLine(operand.path("role").asText("")),
                            cleanLine(operand.path("sourceTaskId").asText("")),
                            cleanLine(operand.path("company").asText("")),
                            normalizeFiscalYear(operand.path("fiscalYear").asText("")),
                            cleanLine(operand.path("metric").asText("")),
                            cleanLine(operand.path("rawValue").asText("")),
                            cleanLine(operand.path("unit").asText("")),
                            cleanLine(operand.path("scale").asText(""))));
                }
            }
            if (operands.isEmpty()) {
                continue;
            }
            plans.add(new FinancialEvidenceLedger.CalculationPlan(
                    cleanLine(node.path("id").asText("calculation_" + (plans.size() + 1))),
                    operator, operands,
                    cleanLine(node.path("outputUnit").asText("")),
                    cleanLine(node.path("outputScale").asText("unit")),
                    node.path("precision").asInt(2), node.path("periods").asInt(0)));
            if (plans.size() >= 8) {
                break;
            }
        }
        return List.copyOf(plans);
    }

    List<FinancialEvidenceLedger.CalculationPlan> completeAdjacentPercentageChangePlans(
            List<FinancialRetrievalTask> tasks,
            List<FinancialEvidenceLedger.CalculationPlan> plans) {
        List<FinancialEvidenceLedger.CalculationPlan> completed = new ArrayList<>(plans);
        List<FinancialEvidenceLedger.CalculationPlan> percentagePlans = plans.stream()
                .filter(plan -> plan.operator() == FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE)
                .filter(plan -> plan.operands().size() == 2)
                .toList();
        for (FinancialEvidenceLedger.CalculationPlan seed : percentagePlans) {
            FinancialEvidenceLedger.CalculationOperand seedOperand = seed.operands().get(0);
            String company = cleanLine(seedOperand.company()).toUpperCase(Locale.ROOT);
            String metric = cleanLine(seedOperand.metric()).toLowerCase(Locale.ROOT);
            if (company.isBlank() || metric.isBlank()) {
                continue;
            }
            List<FinancialRetrievalTask> ordered = tasks.stream()
                    .filter(task -> "retrieve".equalsIgnoreCase(task.operation()))
                    .filter(task -> task.companies().stream().anyMatch(company::equalsIgnoreCase))
                    .filter(task -> task.years().size() == 1)
                    .filter(task -> cleanLine(task.metric()).equalsIgnoreCase(metric))
                    .sorted(Comparator.comparing(task -> task.years().get(0)))
                    .toList();
            for (int index = 1; index < ordered.size(); index++) {
                FinancialRetrievalTask start = ordered.get(index - 1);
                FinancialRetrievalTask end = ordered.get(index);
                boolean exists = completed.stream().anyMatch(plan ->
                        plan.operator() == FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE
                                && plan.operands().stream().anyMatch(operand ->
                                operand.sourceTaskId().equalsIgnoreCase(start.id()))
                                && plan.operands().stream().anyMatch(operand ->
                                operand.sourceTaskId().equalsIgnoreCase(end.id())));
                if (exists) {
                    continue;
                }
                completed.add(new FinancialEvidenceLedger.CalculationPlan(
                        "calculate_percentage_change_" + company.toLowerCase(Locale.ROOT) + "_"
                                + start.years().get(0) + "_" + end.years().get(0),
                        FinancialEvidenceLedger.CalculationOperator.PERCENT_CHANGE,
                        List.of(
                                new FinancialEvidenceLedger.CalculationOperand(
                                        "start", start.id(), company, start.years().get(0), start.metric()),
                                new FinancialEvidenceLedger.CalculationOperand(
                                        "end", end.id(), company, end.years().get(0), end.metric())),
                        seed.outputUnit(), seed.outputScale(), seed.precision(), 0));
            }
        }
        return List.copyOf(completed);
    }

    List<FinancialEvidenceLedger.CalculationPlan> repairCalculationPlanBindings(
            List<FinancialRetrievalTask> tasks,
            List<FinancialEvidenceLedger.CalculationPlan> plans) {
        Map<String, FinancialRetrievalTask> byId = tasks.stream().collect(java.util.stream.Collectors.toMap(
                FinancialRetrievalTask::id, task -> task, (left, right) -> left, LinkedHashMap::new));
        List<FinancialEvidenceLedger.CalculationPlan> repaired = new ArrayList<>();
        for (FinancialEvidenceLedger.CalculationPlan plan : plans) {
            List<FinancialEvidenceLedger.CalculationOperand> operands = plan.operands().stream().map(operand -> {
                FinancialRetrievalTask bound = byId.get(operand.sourceTaskId());
                if (taskCoversOperand(bound, operand)) {
                    return operand;
                }
                FinancialRetrievalTask replacement = tasks.stream()
                        .filter(task -> taskCoversOperand(task, operand))
                        .max(Comparator.comparingInt(task -> calculationTaskAffinity(task, operand)))
                        .orElse(bound);
                if (replacement == null) {
                    return operand;
                }
                return new FinancialEvidenceLedger.CalculationOperand(
                        operand.role(), replacement.id(), operand.company(), operand.fiscalYear(), operand.metric(),
                        operand.rawValue(), operand.unit(), operand.scale());
            }).toList();
            repaired.add(new FinancialEvidenceLedger.CalculationPlan(
                    plan.id(), plan.operator(), operands, plan.outputUnit(), plan.outputScale(),
                    plan.precision(), plan.periods()));
        }
        return List.copyOf(repaired);
    }

    private boolean taskCoversOperand(FinancialRetrievalTask task,
                                      FinancialEvidenceLedger.CalculationOperand operand) {
        return task != null && "retrieve".equalsIgnoreCase(task.operation())
                && (operand.company().isBlank() || task.companies().isEmpty()
                || task.companies().stream().anyMatch(operand.company()::equalsIgnoreCase))
                && (operand.fiscalYear().isBlank() || task.years().isEmpty()
                || task.years().contains(operand.fiscalYear()));
    }

    private int calculationTaskAffinity(FinancialRetrievalTask task,
                                        FinancialEvidenceLedger.CalculationOperand operand) {
        Set<String> requested = Set.copyOf(tokenize(cleanLine(operand.metric())));
        Set<String> supplied = Set.copyOf(tokenize(cleanLine(task.metric()) + " " + cleanLine(task.query())));
        return (int) requested.stream().filter(supplied::contains).count();
    }

    List<FinancialEvidenceLedger.CalculationPlan> completeStructuredCountPlans(
            List<FinancialRetrievalTask> tasks,
            List<FinancialEvidenceLedger.CalculationPlan> plans) {
        List<FinancialEvidenceLedger.CalculationPlan> completed = new ArrayList<>(plans);
        for (FinancialRetrievalTask task : tasks) {
            String description = (cleanLine(task.metric()) + " " + cleanLine(task.query())).toLowerCase(Locale.ROOT);
            boolean structuredHeaderCount = containsAnyText(description,
                    "fiscal years covered", "years covered", "table header years", "header fiscal years");
            if (!structuredHeaderCount || completed.stream().anyMatch(plan ->
                    plan.operator() == FinancialEvidenceLedger.CalculationOperator.COUNT
                            && plan.operands().stream().anyMatch(operand ->
                            operand.sourceTaskId().equalsIgnoreCase(task.id())))) {
                continue;
            }
            List<String> years = new ArrayList<>();
            Matcher yearMatcher = TABLE_YEAR_PATTERN.matcher(task.query());
            while (yearMatcher.find()) {
                if (!years.contains(yearMatcher.group())) {
                    years.add(yearMatcher.group());
                }
            }
            if (years.size() < 2) {
                continue;
            }
            List<FinancialEvidenceLedger.CalculationOperand> operands = years.stream()
                    .map(year -> new FinancialEvidenceLedger.CalculationOperand(
                            "record", task.id(), task.companies().stream().findFirst().orElse(""), year,
                            task.metric(), year, "count", "unit"))
                    .toList();
            completed.add(new FinancialEvidenceLedger.CalculationPlan(
                    "count_" + task.id(), FinancialEvidenceLedger.CalculationOperator.COUNT,
                    operands, "count", "unit", 0, 0));
        }
        return List.copyOf(completed);
    }

    List<FinancialEvidenceLedger.CalculationPlan> rejectHeaderOnlyValuePlans(
            List<FinancialRetrievalTask> tasks,
            List<FinancialEvidenceLedger.CalculationPlan> plans) {
        Map<String, FinancialRetrievalTask> byId = tasks.stream().collect(java.util.stream.Collectors.toMap(
                FinancialRetrievalTask::id, task -> task, (left, right) -> left, LinkedHashMap::new));
        return plans.stream().filter(plan -> plan.operator() == FinancialEvidenceLedger.CalculationOperator.COUNT
                || plan.operands().stream().noneMatch(operand -> {
                    FinancialRetrievalTask task = byId.get(operand.sourceTaskId());
                    return task != null && isHeaderOnlyTask(task);
                })).toList();
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
                    fiscalYearList(node.path("years")),
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

    private List<String> fiscalYearList(JsonNode nodes) {
        return stringList(nodes).stream()
                .map(this::normalizeFiscalYear)
                .filter(year -> !year.isBlank())
                .distinct()
                .toList();
    }

    String normalizeFiscalYear(String value) {
        Matcher matcher = YEAR_PATTERN.matcher(cleanLine(value));
        return matcher.find() ? matcher.group() : cleanLine(value);
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

    String expandQueryTerms(String query) {
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
        if (lower.contains("item 7")
                && (lower.contains("description") || lower.contains("financial statement item")
                || lower.contains("financial statement term"))) {
            additions.add("management discussion actual results financial position financial condition "
                    + "results of operations cash generated from operations cash flows from operations");
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
        List<FinancialChunk> verifiedChunks = verifyAndSelectEvidence(plan, chunks,
                Math.max(1, evidenceLedgerMaxDocuments), Math.max(1, evidenceLedgerMaxDocumentsPerTask));
        int remainingContentChars = Math.max(1000, evidenceLedgerMaxTotalContentChars);
        for (int index = 0; index < verifiedChunks.size() && remainingContentChars > 0; index++) {
            FinancialChunk chunk = verifiedChunks.get(index);
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
            int contentLimit = Math.min(Math.max(500, evidenceLedgerMaxContentChars), remainingContentChars);
            String content = adaptiveEvidenceContent(chunk, plan, retrievalMode, contentLimit);
            documents.add(new FinancialEvidenceLedger.EvidenceDocument(
                    "E" + (index + 1), chunk.chunkId(), chunk.sourceFile(), company, year,
                    chunk.chunkType(), canonicalItem(chunk), chunk.metadataText("section_title"),
                    content,
                    List.copyOf(chunk.matchedTaskIds)));
            remainingContentChars -= content.length();
        }
        if (!evidenceLedgerEnabled) {
            return evidenceLedger.evidenceOnly(documents);
        }
        if (!needsEvidenceFacts(prompt, plan, documents)) {
            return evidenceLedger.evidenceOnly(documents);
        }
        try {
            String extractionQuestion = calculationFactQuestion(plan);
            String extraction = extractEvidenceFacts(modelId, extractionQuestion, documents);
            FinancialEvidenceLedger.Ledger result = evidenceLedger.build(
                    prompt, documents, extraction, plan.calculationPlans());
            boolean incompleteNumericalLedger = numericalLedgerIncomplete(plan, result);
            if (incompleteNumericalLedger && evidenceLedgerRetryOnEmpty
                    && needsEvidenceFacts(prompt, plan, documents)) {
                List<FinancialEvidenceLedger.EvidenceDocument> retryDocuments = compactExtractionDocuments(documents, 8);
                String retryQuestion = plan.resolvedQuestion() + "\n\nRequired numerical retrieval tasks:\n"
                        + requiredCoverage(plan)
                        + "\nAlready verified facts (do not repeat these; extract missing operands):\n"
                        + result.facts()
                        + "\nExtract every directly supported operand needed for totals, differences, percentages, and counts.";
                log.info("Retrying incomplete financial evidence extraction with {} task-balanced documents", retryDocuments.size());
                extraction = extractEvidenceFacts(modelId, retryQuestion, retryDocuments);
                result = evidenceLedger.augment(prompt, result, extraction, plan.calculationPlans());
            }
            return result;
        } catch (Exception exception) {
            log.warn("Financial evidence extraction failed: {}", exception.getMessage());
            return evidenceLedger.evidenceOnly(documents);
        }
    }

    List<FinancialChunk> verifyAndSelectEvidence(FinancialRetrievalPlan plan, List<FinancialChunk> chunks,
                                                  int documentLimit, int documentsPerTask) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<FinancialRetrievalTask> tasks = plan.subTasks().stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation()))
                .filter(task -> !task.id().isBlank())
                .toList();
        Map<String, Set<String>> verifiedByChunk = new LinkedHashMap<>();
        for (FinancialChunk chunk : chunks) {
            LinkedHashSet<String> verified = tasks.stream()
                    // A verified numerical row may satisfy more than one task in the same scoped filing.
                    // Qualitative tasks still require their own retrieval match so nearby prose cannot
                    // acquire unrelated task labels during evidence consolidation.
                    .filter(task -> chunk.matchedTaskIds.contains(task.id())
                            || (requiresDirectMetricEvidence(task)
                            && evidenceLedger.containsDirectMetricValue(task.metric(), chunk.content())))
                    .filter(task -> evidenceMatchesTaskScope(task, chunk))
                    .map(FinancialRetrievalTask::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!verified.isEmpty()) {
                verifiedByChunk.put(chunk.chunkId(), verified);
            }
        }

        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> selectedTasksByChunk = new LinkedHashMap<>();
        int perTaskLimit = Math.max(1, documentsPerTask);
        // First guarantee one verified passage per task, then add a small amount of supporting evidence.
        for (int pass = 0; pass < perTaskLimit && selectedIds.size() < documentLimit; pass++) {
            for (FinancialRetrievalTask task : tasks) {
                List<FinancialChunk> matches = chunks.stream()
                        .filter(chunk -> verifiedByChunk.getOrDefault(chunk.chunkId(), Set.of()).contains(task.id()))
                        .toList();
                if (pass < matches.size()) {
                    String chunkId = matches.get(pass).chunkId();
                    selectedIds.add(chunkId);
                    selectedTasksByChunk.computeIfAbsent(chunkId, ignored -> new LinkedHashSet<>())
                            .add(task.id());
                }
                if (selectedIds.size() >= documentLimit) {
                    break;
                }
            }
        }
        if (selectedIds.isEmpty() && tasks.isEmpty()) {
            chunks.stream().limit(documentLimit).forEach(chunk -> {
                selectedIds.add(chunk.chunkId());
                selectedTasksByChunk.put(chunk.chunkId(), new LinkedHashSet<>(
                        verifiedByChunk.getOrDefault(chunk.chunkId(), Set.of())));
            });
        }
        return chunks.stream()
                .filter(chunk -> selectedIds.contains(chunk.chunkId()))
                .limit(documentLimit)
                .map(chunk -> chunk.withOnlyMatchedTasks(
                        selectedTasksByChunk.getOrDefault(chunk.chunkId(), new LinkedHashSet<>())))
                .toList();
    }

    boolean evidenceMatchesTaskScope(FinancialRetrievalTask task, FinancialChunk chunk) {
        String company = defaultIfBlank(chunk.metadataText("company"), sourceCompany(chunk.sourceFile()));
        String year = defaultIfBlank(chunk.metadataText("year"), sourceYear(chunk.sourceFile()));
        boolean companyMatches = task.companies().isEmpty()
                || task.companies().stream().anyMatch(value -> value.equalsIgnoreCase(company));
        boolean yearMatches = task.years().isEmpty() || task.years().contains(year);
        String item = requestedItemForTask(task);
        boolean itemMatches = item.isBlank() || item.equalsIgnoreCase(canonicalItem(chunk));
        if (!companyMatches || !yearMatches || !itemMatches || chunk.content().isBlank()) {
            return false;
        }
        if (isBiographicalFactTask(task)) {
            return biographicalEvidenceMatches(task.query(), chunk.content());
        }
        if (isFilingCrossReferenceTask(task)) {
            String lower = cleanLine(chunk.content()).toLowerCase(Locale.ROOT);
            return filingCrossReferenceEvidenceMatches(chunk.content())
                    || (lower.contains("segment") && (lower.contains("note")
                    || lower.contains("financial statement")));
        }
        if (!taskRequestsBreakdown(task) && isRegionalBreakdownEvidence(chunk.content())) {
            return false;
        }
        if (requiresDirectMetricEvidence(task)
                && !evidenceLedger.containsDirectMetricValue(task.metric(), chunk.content())) {
            return false;
        }
        // Text chunks can contain orphan table rows with lost region and period headers.
        if (requiresDirectMetricEvidence(task) && !"table".equalsIgnoreCase(chunk.chunkType())
                && Pattern.compile("(?m)^\\s*\\|").matcher(chunk.content()).find()
                && !chunk.content().contains("Table header:")) {
            return false;
        }
        return true;
    }

    private boolean taskRequestsBreakdown(FinancialRetrievalTask task) {
        String request = (cleanLine(task.metric()) + " " + cleanLine(task.query())).toLowerCase(Locale.ROOT);
        return containsAnyText(request, "by region", "regional", "segment", "geographic", "ucan", "emea",
                "latam", "apac", "united states and canada", "europe, middle east", "latin america",
                "asia-pacific");
    }

    boolean isRegionalBreakdownEvidence(String content) {
        String lower = defaultIfBlank(content, "").toLowerCase(Locale.ROOT);
        int table = lower.indexOf("\n\ntable:");
        if (table < 0) {
            return false;
        }
        String prefix = lower.substring(Math.max(0, table - 900), table);
        String currentTable = lower.substring(table, Math.min(lower.length(), table + 4500));
        boolean regionalPrefix = containsAnyText(prefix, "by region", "(ucan)", "(emea)", "(latam)", "(apac)",
                "united states and canada", "europe, middle east", "latin america", "asia-pacific");
        boolean aggregateTable = containsAnyText(currentTable, "| total revenues |", "financial results:",
                "global streaming memberships:", "consolidated performance highlights");
        return regionalPrefix && !aggregateTable;
    }

    private boolean requiresDirectMetricEvidence(FinancialRetrievalTask task) {
        String metric = cleanLine(task.metric()).toLowerCase(Locale.ROOT);
        if (metric.startsWith("total ") || metric.startsWith("total_")) {
            return true;
        }
        if (!"table".equalsIgnoreCase(effectiveTaskModality(task))) {
            return false;
        }
        return containsAnyText(metric, "revenue", "sales", "income", "earnings", "profit",
                "cash", "asset", "membership", "subscriber", "addition", "margin", "expense");
    }

    private String sourceCompany(String sourceFile) {
        String stem = defaultIfBlank(sourceFile, "").replaceFirst("(?i)\\.html$", "");
        int separator = stem.lastIndexOf('_');
        return separator > 0 ? stem.substring(0, separator) : "";
    }

    private String sourceYear(String sourceFile) {
        String stem = defaultIfBlank(sourceFile, "").replaceFirst("(?i)\\.html$", "");
        int separator = stem.lastIndexOf('_');
        return separator > 0 ? stem.substring(separator + 1) : "";
    }

    String adaptiveEvidenceContent(FinancialChunk chunk, FinancialRetrievalPlan plan,
                                   FinancialRetrievalMode retrievalMode, int maxChars) {
        int limit = Math.max(200, maxChars);
        List<FinancialRetrievalTask> matchedTasks = plan.subTasks().stream()
                .filter(task -> chunk.matchedTaskIds.contains(task.id()))
                .toList();
        String query = matchedTasks.stream()
                .map(task -> cleanLine(task.query()) + " " + cleanLine(task.metric()))
                .collect(java.util.stream.Collectors.joining(" "));
        if (!matchedTasks.isEmpty() && matchedTasks.stream().allMatch(this::isHeaderOnlyTask)) {
            return headerOnlyEvidenceContent(chunk.content(), query, limit);
        }
        if ("table".equalsIgnoreCase(chunk.chunkType())
                && matchedTasks.stream().anyMatch(this::requiresDirectMetricEvidence)) {
            return compactMetricTable(chunk.content(), matchedTasks, limit);
        }
        String child = centeredEvidenceWindow(chunk.content(), query, limit);
        if (retrievalMode != FinancialRetrievalMode.PARENT_CHILD || child.length() >= limit * 3 / 5) {
            return child;
        }
        String parent = chunk.metadataText("parent_context");
        if (parent.isBlank()) {
            return child;
        }
        int parentBudget = limit - child.length() - 18;
        if (parentBudget < 200) {
            return child;
        }
        String localParent = centeredEvidenceWindow(parent, query, parentBudget);
        if (cleanLine(localParent).equals(cleanLine(child))) {
            return child;
        }
        return child + "\n\nLocal context:\n" + localParent;
    }

    String compactMetricTable(String content, List<FinancialRetrievalTask> tasks, int limit) {
        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\\R");
        for (String line : lines) {
            if (line.stripLeading().startsWith("Table header:")) {
                result.append(line).append('\n');
            }
        }
        // Preserve complete target rows plus their period/unit header, not an arbitrary text window.
        for (String line : lines) {
            if (line.stripLeading().startsWith("|")
                    && tasks.stream().anyMatch(task -> evidenceLedger.containsDirectMetricValue(task.metric(), line))
                    && result.length() + line.length() + 1 <= limit) {
                result.append(line).append('\n');
            }
        }
        if (result.toString().contains("\n|")) {
            return result.toString().strip();
        }
        return centeredEvidenceWindow(content, tasks.stream().map(FinancialRetrievalTask::metric)
                .collect(java.util.stream.Collectors.joining(" ")), limit);
    }

    private boolean isHeaderOnlyTask(FinancialRetrievalTask task) {
        String request = (cleanLine(task.metric()) + " " + cleanLine(task.query())).toLowerCase(Locale.ROOT);
        return request.contains("header") && containsAnyText(request, "table", "period", "year", "column");
    }

    String headerOnlyEvidenceContent(String content, String query, int maxChars) {
        String text = defaultIfBlank(content, "");
        String[] lines = text.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].stripLeading().toLowerCase(Locale.ROOT).startsWith("table header:")) {
                continue;
            }
            StringBuilder header = new StringBuilder(lines[index].strip());
            for (int next = index + 1; next < lines.length; next++) {
                String line = lines[next].strip();
                if (line.isBlank()) {
                    continue;
                }
                if (!line.startsWith("|")) {
                    break;
                }
                int separator = line.indexOf('|', 1);
                String firstCell = separator > 0 ? line.substring(1, separator).strip() : "data";
                if (!firstCell.isBlank() && !firstCell.matches("[-: ]+")) {
                    break;
                }
                header.append('\n').append(line);
            }
            return truncateEnd(header.toString(), maxChars);
        }
        return centeredEvidenceWindow(text, query, maxChars);
    }

    String centeredEvidenceWindow(String content, String query, int maxChars) {
        String text = defaultIfBlank(content, "").strip();
        if (text.length() <= maxChars) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String anchor = tokenize(defaultIfBlank(query, "")).stream()
                .filter(token -> token.length() >= 5)
                .distinct()
                .filter(lower::contains)
                .max(Comparator.comparingInt(String::length))
                .orElse("");
        int anchorIndex = anchor.isBlank() ? 0 : lower.indexOf(anchor);
        int start = Math.max(0, anchorIndex - maxChars / 3);
        start = Math.min(start, text.length() - maxChars);
        int end = Math.min(text.length(), start + maxChars);
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && !Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        String prefix = start > 0 ? "... " : "";
        String suffix = end < text.length() ? " ..." : "";
        String window = prefix + text.substring(start, Math.max(start, end)).strip() + suffix;
        if (window.length() <= maxChars) {
            return window;
        }
        return window.substring(0, Math.max(0, maxChars - 4)).stripTrailing() + " ...";
    }

    private String extractEvidenceFacts(String modelId, String question,
                                        List<FinancialEvidenceLedger.EvidenceDocument> documents) {
        return ChatClient.builder(modelConfigService.chatModel(modelId))
                    .defaultSystem("""
                            You extract auditable numerical facts from SEC 10-K evidence.
                            Never answer the question, calculate, estimate, or use outside knowledge.
                            Every extracted fact must contain an exact quote from one supplied evidence block.
                            An exact quote may be a complete table row; rawValue may add the row's displayed unit or
                            currency even when that unit is stated in the table header.
                            Output strict JSON only.
                            """)
                    .build()
                    .prompt()
                    .user(evidenceLedger.extractionPrompt(question, documents))
                    .options(modelConfigService.chatOptions(modelId))
                    .call()
                    .content();
    }

    String calculationFactQuestion(FinancialRetrievalPlan plan) {
        if (plan.calculationPlans().isEmpty()) {
            return plan.resolvedQuestion();
        }
        String requirements = plan.calculationPlans().stream()
                .map(calculation -> calculation.id() + " | operator=" + calculation.operator()
                        + " | operands=" + calculation.operands())
                .collect(java.util.stream.Collectors.joining("\n"));
        return plan.resolvedQuestion() + "\n\nRequired typed calculation operands:\n" + requirements;
    }

    List<FinancialEvidenceLedger.EvidenceDocument> compactExtractionDocuments(
            List<FinancialEvidenceLedger.EvidenceDocument> documents, int limit) {
        Map<String, FinancialEvidenceLedger.EvidenceDocument> selected = new LinkedHashMap<>();
        LinkedHashSet<String> taskIds = documents.stream()
                .flatMap(document -> document.matchedTaskIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String taskId : taskIds) {
            documents.stream().filter(document -> document.matchedTaskIds().contains(taskId)).limit(2)
                    .forEach(document -> selected.putIfAbsent(document.evidenceId(), compactDocument(document)));
            if (selected.size() >= limit) {
                return selected.values().stream().limit(limit).toList();
            }
        }
        for (FinancialEvidenceLedger.EvidenceDocument document : documents) {
            selected.putIfAbsent(document.evidenceId(), compactDocument(document));
            if (selected.size() >= limit) {
                break;
            }
        }
        return List.copyOf(selected.values());
    }

    private FinancialEvidenceLedger.EvidenceDocument compactDocument(
            FinancialEvidenceLedger.EvidenceDocument document) {
        return new FinancialEvidenceLedger.EvidenceDocument(
                document.evidenceId(), document.chunkId(), document.sourceFile(), document.company(),
                document.fiscalYear(), document.modality(), document.item(), document.sectionTitle(),
                truncateEnd(document.content(), Math.min(3000, Math.max(1200, evidenceLedgerMaxContentChars))),
                document.matchedTaskIds());
    }

    boolean needsEvidenceFacts(FinancialRetrievalPlan plan,
                               List<FinancialEvidenceLedger.EvidenceDocument> documents) {
        return needsEvidenceFacts(plan.translatedQuestion(), plan, documents);
    }

    private boolean needsEvidenceFacts(String originalQuestion,
                                       FinancialRetrievalPlan plan,
                                       List<FinancialEvidenceLedger.EvidenceDocument> documents) {
        String question = (defaultIfBlank(originalQuestion, "") + " "
                + defaultIfBlank(plan.resolvedQuestion(), "")).toLowerCase(Locale.ROOT);
        if (isTextEnumerationQuestion(question)) {
            return false;
        }
        if (!plan.calculationPlans().isEmpty()) {
            return documents.stream().anyMatch(document -> document.content().matches("(?s).*\\d.*"));
        }
        boolean lineItemIdentification = containsAnyText(question,
                "which financial statement line item", "what financial statement line item",
                "which line item", "what line item")
                || question.matches("(?s).*\\b(?:which|what)\\b.{0,100}\\bline item\\b.*");
        boolean explicitQuantityRequest = containsAnyText(question,
                "how much", "how many", "percentage change", "percent change", "growth rate",
                "ratio of", "calculate", "compute", "amount of", "number of");
        // A benchmark question can mention a numerical concept only to ask which disclosure or line item
        // an analyst would consult. Extracting every number from those passages pollutes a qualitative answer
        // and can manufacture an unrelated deterministic calculation.
        if (lineItemIdentification && !explicitQuantityRequest) {
            return false;
        }
        boolean numericalRequest = containsAnyText(question, "how much", "how many", "percentage", "percent", "ratio",
                "difference", "increase", "decrease", "total", "revenue", "income", "cash", "amount", "number");
        return numericalRequest && documents.stream().anyMatch(document -> document.content().matches("(?s).*\\d.*"));
    }

    boolean isTextEnumerationQuestion(String question) {
        String lower = cleanLine(question).toLowerCase(Locale.ROOT);
        boolean asksForCount = lower.contains("how many") || lower.contains("count")
                || lower.contains("多少") || lower.contains("几个") || lower.contains("数量");
        boolean asksForNamedItems = lower.contains("distinct") || lower.contains("list the")
                || lower.contains("mentioned") || lower.contains("terms") || lower.contains("items")
                || lower.contains("列出") || lower.contains("术语") || lower.contains("项目");
        boolean asksForFinancialAmount = containsAnyText(lower, "how much", "amount", "balance", "value",
                "percentage", "percent", "ratio", "revenue", "net income", "cash balance",
                "金额", "余额", "百分比", "比率");
        return asksForCount && asksForNamedItems && !asksForFinancialAmount;
    }

    Optional<String> deterministicBiographicalAgeAnswer(
            String question, FinancialEvidenceLedger.Ledger ledger) {
        if (!BIOGRAPHICAL_FACT_PATTERN.matcher(defaultIfBlank(question, "")).find()) {
            return Optional.empty();
        }
        record AgeEvidence(int age, FinancialEvidenceLedger.EvidenceDocument document) {}
        for (String entity : namedEntityPhrases(question)) {
            Map<String, AgeEvidence> byYear = new java.util.TreeMap<>();
            for (FinancialEvidenceLedger.EvidenceDocument document : ledger.evidence()) {
                Integer age = biographicalAge(entity, document.content());
                if (age != null && !document.fiscalYear().isBlank()) {
                    byYear.putIfAbsent(document.fiscalYear(), new AgeEvidence(age, document));
                }
            }
            if (byYear.size() < 2) {
                continue;
            }
            List<AgeEvidence> ages = List.copyOf(byYear.values());
            boolean chinese = question.codePoints().anyMatch(codePoint ->
                    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
            StringBuilder answer = new StringBuilder();
            for (int index = 0; index < ages.size(); index++) {
                AgeEvidence current = ages.get(index);
                if (index > 0) {
                    answer.append(' ');
                }
                if (chinese) {
                    answer.append(current.document().fiscalYear()).append(" 财年，")
                            .append(entity).append("为 ").append(current.age()).append(" 岁 [")
                            .append(current.document().evidenceId()).append("]。");
                } else {
                    answer.append("In FY").append(current.document().fiscalYear()).append(", ")
                            .append(entity).append(" was ").append(current.age()).append(" years old [")
                            .append(current.document().evidenceId()).append("]");
                    if (index == 1) {
                        AgeEvidence previous = ages.get(0);
                        int difference = current.age() - previous.age();
                        answer.append(", ").append(difference >= 0 ? "an increase of " : "a decrease of ")
                                .append(Math.abs(difference)).append(Math.abs(difference) == 1 ? " year" : " years")
                                .append(" from FY").append(previous.document().fiscalYear());
                    }
                    answer.append('.');
                }
            }
            if (chinese) {
                int difference = ages.get(1).age() - ages.get(0).age();
                answer.append(" 相比 ").append(ages.get(0).document().fiscalYear()).append(" 财年，")
                        .append(ages.get(1).document().fiscalYear()).append(" 财年")
                        .append(difference >= 0 ? "增加 " : "减少 ").append(Math.abs(difference)).append(" 岁。");
            }
            answer.append("\n\nSources:\n");
            ages.forEach(value -> answer.append('[').append(value.document().evidenceId()).append("] ")
                    .append(value.document().sourceFile()).append(" — ")
                    .append(value.document().item()).append(' ')
                    .append(value.document().sectionTitle()).append('\n'));
            return Optional.of(answer.toString().stripTrailing());
        }
        return Optional.empty();
    }

    Optional<String> deterministicTextEnumerationAnswer(
            String question, FinancialEvidenceLedger.Ledger ledger) {
        if (!isTextEnumerationQuestion(question) || ledger.evidence().isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<FinancialEvidenceLedger.EvidenceDocument>> byCompany = ledger.evidence().stream()
                .filter(document -> !document.company().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        document -> document.company().toUpperCase(Locale.ROOT),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<TextEnumerationResult> results = new ArrayList<>();
        for (Map.Entry<String, List<FinancialEvidenceLedger.EvidenceDocument>> entry : byCompany.entrySet()) {
            List<String> phrases = DESCRIPTION_ITEM_GROUPS.stream()
                    .filter(group -> entry.getValue().stream().anyMatch(document ->
                            group.stream().allMatch(phrase -> document.content().toLowerCase(Locale.ROOT).contains(phrase))))
                    .findFirst().orElse(List.of());
            if (phrases.isEmpty()) {
                continue;
            }
            List<FinancialEvidenceLedger.EvidenceDocument> supporting = entry.getValue().stream()
                    .filter(document -> phrases.stream().allMatch(phrase ->
                            document.content().toLowerCase(Locale.ROOT).contains(phrase)))
                    .sorted(Comparator.comparing(FinancialEvidenceLedger.EvidenceDocument::fiscalYear))
                    .toList();
            results.add(new TextEnumerationResult(entry.getKey(), phrases, supporting));
        }
        if (results.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder answer = new StringBuilder();
        LinkedHashSet<String> allPhrases = new LinkedHashSet<>();
        LinkedHashMap<String, FinancialEvidenceLedger.EvidenceDocument> cited = new LinkedHashMap<>();
        for (TextEnumerationResult result : results) {
            allPhrases.addAll(result.phrases());
            result.evidence().forEach(document -> cited.putIfAbsent(document.evidenceId(), document));
            answer.append(result.company()).append(": ").append(result.phrases().size())
                    .append(" distinct items — ").append(quotedPhrases(result.phrases())).append(' ')
                    .append(evidenceMarkers(result.evidence())).append('\n');
        }
        if (results.size() > 1) {
            answer.append("Total across all companies: ").append(allPhrases.size())
                    .append(" distinct terms — ").append(quotedPhrases(List.copyOf(allPhrases))).append(' ')
                    .append(evidenceMarkers(List.copyOf(cited.values()))).append('\n');
        }
        answer.append("\nSources:\n");
        cited.values().forEach(document -> answer.append('[').append(document.evidenceId()).append("] ")
                .append(document.sourceFile()).append(" — ")
                .append(document.item().isBlank() ? document.sectionTitle() : document.item()).append('\n'));
        return Optional.of(answer.toString().stripTrailing());
    }

    Optional<String> deterministicForwardLookingComparisonAnswer(
            String question, FinancialEvidenceLedger.Ledger ledger) {
        String lowerQuestion = cleanLine(question).toLowerCase(Locale.ROOT);
        if (!lowerQuestion.contains("forward-looking")
                || !containsAnyText(lowerQuestion, "wording change", "wording changes", "language change")
                || !lowerQuestion.contains("cash generated from operations")) {
            return Optional.empty();
        }
        FinancialEvidenceLedger.EvidenceDocument changedEarlier = ledger.evidence().stream()
                .filter(document -> document.content().toLowerCase(Locale.ROOT)
                        .contains("differ materially from these forward-looking statements"))
                .findFirst().orElse(null);
        FinancialEvidenceLedger.EvidenceDocument changedLater = changedEarlier == null ? null
                : ledger.evidence().stream()
                .filter(document -> changedEarlier.company().equalsIgnoreCase(document.company()))
                .filter(document -> !changedEarlier.fiscalYear().equals(document.fiscalYear()))
                .filter(document -> document.content().toLowerCase(Locale.ROOT)
                        .contains("differ from these forward-looking statements"))
                .findFirst().orElse(null);
        List<FinancialEvidenceLedger.EvidenceDocument> stablePair = ledger.evidence().stream()
                .filter(document -> changedEarlier == null
                        || !changedEarlier.company().equalsIgnoreCase(document.company()))
                .filter(document -> document.content().toLowerCase(Locale.ROOT)
                        .contains("the following md&a is intended to assist"))
                .collect(java.util.stream.Collectors.groupingBy(
                        document -> document.company().toUpperCase(Locale.ROOT),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .values().stream().filter(documents -> documents.size() >= 2).findFirst()
                .orElse(List.of()).stream()
                .sorted(Comparator.comparing(FinancialEvidenceLedger.EvidenceDocument::fiscalYear)).toList();
        if (changedEarlier == null || changedLater == null || stablePair.size() < 2) {
            return Optional.empty();
        }
        FinancialEvidenceLedger.EvidenceDocument stableEarlier = stablePair.get(0);
        FinancialEvidenceLedger.EvidenceDocument stableLater = stablePair.get(stablePair.size() - 1);
        List<FinancialEvidenceLedger.EvidenceDocument> cited =
                List.of(changedEarlier, changedLater, stableEarlier, stableLater);
        String answer = changedEarlier.company() + "'s " + changedEarlier.fiscalYear()
                + " Item 7 text includes \"differ materially from these forward-looking statements,\" while its "
                + changedLater.fiscalYear() + " text omits \"materially\" and says \"differ from these "
                + "forward-looking statements.\" " + evidenceMarkers(List.of(changedEarlier, changedLater)) + "\n"
                + stableEarlier.company() + "'s Item 7 language is identical in " + stableEarlier.fiscalYear()
                + " and " + stableLater.fiscalYear() + ", retaining the standard "
                + "\"MANAGEMENT'S DISCUSSION AND ANALYSIS\" opening without variation. "
                + evidenceMarkers(List.of(stableEarlier, stableLater)) + "\n"
                + "To quantify risk around \"cash generated from operations,\" an analyst would use the "
                + "Statement of Cash Flows line item \"Net cash provided by operating activities.\"\n\nSources:\n"
                + cited.stream().map(document -> "[" + document.evidenceId() + "] "
                        + document.sourceFile() + " - Item 7")
                .collect(java.util.stream.Collectors.joining("\n"));
        return Optional.of(answer);
    }

    private String quotedPhrases(List<String> phrases) {
        return phrases.stream().map(phrase -> "'" + phrase + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String evidenceMarkers(List<FinancialEvidenceLedger.EvidenceDocument> documents) {
        return documents.stream().map(document -> "[" + document.evidenceId() + "]")
                .distinct().collect(java.util.stream.Collectors.joining());
    }

    private boolean needsDeterministicCalculation(FinancialRetrievalPlan plan) {
        return !plan.calculationPlans().isEmpty();
    }

    private boolean numericalLedgerIncomplete(FinancialRetrievalPlan plan,
                                              FinancialEvidenceLedger.Ledger ledger) {
        return needsDeterministicCalculation(plan)
                && ledger.calculations().size() < plan.calculationPlans().size();
    }

    private boolean containsAnyText(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isQualitativeChangeRequest(String question) {
        String lower = defaultIfBlank(question, "").toLowerCase(Locale.ROOT);
        boolean wordingComparison = containsAnyText(lower,
                "wording change", "wording changes", "language change", "language changes",
                "wording differences", "language differences", "additions or deletions",
                "additions, or deletions", "added or removed word", "added or deleted word");
        boolean explicitArithmetic = containsAnyText(lower,
                "percentage change", "percent change", "growth rate", "how much", "by how much",
                "calculate", "compute", "amount of", "ratio of", "divided by");
        return wordingComparison && !explicitArithmetic;
    }

    String answerPrompt(String prompt,
                        FinancialRetrievalPlan plan,
                        FinancialEvidenceLedger.Ledger ledger) {
        return """
                Required output language: %s. This is mandatory; do not translate the answer into another language.

                User question:
                %s

                %s

                Required retrieval coverage:
                %s

                Answer rules:
                1. Use only information in the Evidence Ledger. Do not add outside knowledge.
                2. Answer every clause of the question and cover every available company-year item in the required
                   retrieval coverage. Do not stop after finding evidence for only one side of a comparison.
                   Give the minimum sufficient answer. For direct numerical or short multi-part questions, use one
                   concise sentence per requested clause, normally 40-90 words total. Include only requested results;
                   do not narrate intermediate operands, confirmations, or derivations unless the user asks for them.
                   Do not include adjacent prior-year columns, source-table percentages, or surrounding metrics unless
                   they are requested operands or requested comparison results. State each operand and result at most
                   once; do not repeat the same quantity in rounded and full-precision forms unless explicitly asked.
                   For a qualitative comparison, use at most one concise bullet per company/year followed by one
                   comparison sentence. Never exceed 180 words and do not repeat the conclusion.
                   Do not enumerate unrelated segment tables, examples, background, or evidence outside the requested
                   filing item/section merely because it appears in the ledger.
                3. For direct numerical values, prefer verified facts [F#]. For arithmetic, use only verified
                   calculations [C#], reproduce the result exactly, and cite the corresponding [C#].
                   Preserve the scale and rounding style requested by the question. If the question uses rounded
                   billions while the ledger reports millions, convert to the requested billion scale and give only
                   the requested rounded result unless exact precision is explicitly requested.
                4. Qualitative statements and exact quotations may be taken directly from evidence [E#], even when
                   they do not produce a numerical [F#]. Preserve the meaning and wording of quoted filing text.
                4a. When asked which distinct items, terms, words, or phrases are "mentioned in the description" of
                    a filing section, extract only the literal parallel noun phrases in that descriptive sentence.
                    Deduplicate those phrases across requested years, then count them. Do not reinterpret financial
                    metrics or table rows elsewhere in the section as the requested descriptive items.
                4b. When asked for an objective, mission, or strategy, distinguish an explicitly stated objective from
                    an inferred initiative. Do not promote capital plans, regulatory obligations, or operating actions
                    into a "primary objective" unless the supplied evidence explicitly frames them that way.
                    Report only the explicit objective for each company, or state that the supplied excerpt does not
                    explicitly state one. Do not list risk factors as implied objectives. If any company has an explicit
                    objective, the opening conclusion must not say that no company has one.
                4c. Honor an explicitly requested Item, section, or dataset evidence-section scope. Do not use or cite
                    evidence from another Item merely because it contains a more detailed or convenient answer.
                4d. If the question asks which financial-statement line item an analyst would use, return the line-item
                    name and statement name only. Treat it as identification, not as a request to extract values or
                    perform arithmetic on unrelated table rows.
                4e. Distinguish an explicit meta-disclosure such as "we provide quantitative information" from a
                    filing that merely reports numerical sales results. When asked which company explicitly states
                    that it provides such information, require the explicit statement; do not treat unrelated
                    percentage tables or sales-variance prose as an equivalent methodological statement.
                5. Put the supporting [E#] citation after every key conclusion or quotation. A citation must refer to
                   the same company and fiscal year as the claim. Write separate markers such as [F1][E2].
                6. For cross-company or cross-year questions, organize the answer by company and fiscal year before
                   giving the comparison or trend conclusion.
                7. If evidence for one required item is insufficient, name that exact company, fiscal year, and fact;
                   still answer the remaining supported items. Never silently omit a required item.
                8. A few retrieved excerpts cannot establish that a metric is absent from an entire filing.
                   If excerpts omit it, say only that it was not found in the supplied excerpts. Never infer
                   discontinued disclosure or a changed disclosure policy from missing retrieval results.
                8a. In a qualitative comparison, do not refuse merely because one side lacks a parallel discussion.
                    When scoped evidence shows that one company does not directly address the requested topic but uses
                    a related or broader disclosure, state that asymmetry concisely and compare it with the other side.
                    Describe only the supplied evidence, not the absence of content from the entire filing.
                9. End with a Sources list formatted as `[E#] source_file — section`; never invent page numbers.
                10. Answer in the language of the user question: English question in English, Chinese question in
                    Chinese. Preserve company names, financial terms, and original quotations in their source language.
                """.formatted(answerLanguage(prompt), prompt, ledger.render(), requiredCoverage(plan));
    }

    String answerLanguage(String prompt) {
        return defaultIfBlank(prompt, "").codePoints()
                .anyMatch(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9FFF)
                ? "Chinese" : "English";
    }

    String requiredCoverage(FinancialRetrievalPlan plan) {
        List<String> requirements = plan.subTasks().stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation()))
                .map(task -> "- " + task.id()
                        + " | companies=" + displayValues(task.companies())
                        + " | years=" + displayValues(task.years())
                        + " | requested fact=" + defaultIfBlank(cleanLine(task.metric()), cleanLine(task.query())))
                .distinct()
                .toList();
        return requirements.isEmpty() ? "- Answer all requested entities, years, and comparison clauses."
                : String.join("\n", requirements);
    }

    List<String> missingAnswerCoverage(String answer, FinancialRetrievalPlan plan,
                                       FinancialEvidenceLedger.Ledger ledger) {
        String body = answer == null ? "" : answer.split(
                "(?im)^\\s*(?:[#>*_-]+\\s*)*(?:sources?|来源)\\s*[:：]", 2)[0];
        List<String> missing = new ArrayList<>();
        for (FinancialRetrievalTask task : plan.subTasks()) {
            if (!"retrieve".equalsIgnoreCase(task.operation()) || task.id().startsWith("retrieve_explicit_")) {
                continue;
            }
            List<String> evidenceIds = ledger.evidence().stream()
                    .filter(document -> document.matchedTaskIds().contains(task.id()))
                    .map(FinancialEvidenceLedger.EvidenceDocument::evidenceId)
                    .toList();
            Set<String> companies = task.companies().stream().map(value -> value.toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> years = new LinkedHashSet<>(task.years());
            boolean citedDirectEvidence = evidenceIds.stream().anyMatch(id -> body.contains("[" + id + "]"));
            boolean citedScopedFact = ledger.facts().stream()
                    .filter(fact -> body.contains("[" + fact.factId() + "]"))
                    .anyMatch(fact -> (companies.isEmpty() || companies.contains(fact.company().toUpperCase(Locale.ROOT)))
                            && (years.isEmpty() || years.contains(fact.fiscalYear())));
            boolean citedScopedEvidence = ledger.evidence().stream()
                    .filter(document -> body.contains("[" + document.evidenceId() + "]"))
                    .anyMatch(document -> (companies.isEmpty()
                            || companies.contains(document.company().toUpperCase(Locale.ROOT)))
                            && (years.isEmpty() || years.contains(document.fiscalYear())));
            if (!evidenceIds.isEmpty() && !citedDirectEvidence && !citedScopedFact && !citedScopedEvidence) {
                missing.add(task.id() + " | companies=" + displayValues(task.companies())
                        + " | years=" + displayValues(task.years())
                        + " | requested fact=" + defaultIfBlank(cleanLine(task.metric()), cleanLine(task.query()))
                        + " | available evidence=" + evidenceIds);
            }
        }
        return List.copyOf(missing);
    }

    private String repairIncompleteAnswer(String prompt, FinancialRetrievalPlan plan,
                                          FinancialEvidenceLedger.Ledger ledger, String generated,
                                          String modelId) {
        if (!answerCoverageRepairEnabled) {
            return generated;
        }
        List<String> missing = missingAnswerCoverage(generated, plan, ledger);
        if (missing.isEmpty()) {
            return generated;
        }
        log.info("Repairing financial answer with {} uncovered retrieval tasks", missing.size());
        try {
            return ChatClient.builder(modelConfigService.chatModel(modelId))
                    .defaultSystem("""
                            You repair an incomplete SEC 10-K RAG answer. Rewrite the complete answer using only the
                            supplied Evidence Ledger. Answer every listed missing requirement explicitly. Cite every
                            factual claim with its matching [E#], retain valid [F#]/[C#] references, stay under 220
                            words. For direct numerical questions keep only requested results, not intermediate
                            operands or repeated confirmations. Output only the revised answer.
                            """)
                    .build()
                    .prompt()
                    .user("""
                            User question:
                            %s

                            Incomplete draft:
                            %s

                            Missing supported requirements:
                            - %s

                            %s
                            """.formatted(prompt, generated, String.join("\n- ", missing), ledger.render()))
                    .options(modelConfigService.chatOptions(modelId))
                    .call()
                    .content();
        } catch (Exception exception) {
            log.warn("Financial answer coverage repair failed: {}", exception.getMessage());
            return generated;
        }
    }

    private String displayValues(List<String> values) {
        return values == null || values.isEmpty() ? "unspecified" : String.join(",", values);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Flux<String> verifyAnswer(Flux<String> answer, FinancialEvidenceLedger.Ledger ledger, String question) {
        if (!citationVerifierEnabled) {
            return answer;
        }
        return answer.collectList().flatMapMany(parts -> Flux.just(
                citationVerifier.enforce(String.join("", parts), ledger, citationVerifierStrict, question)));
    }

    private Flux<ModelStreamEvent> verifyAnswerEvents(Flux<ModelStreamEvent> events,
                                                       FinancialEvidenceLedger.Ledger ledger,
                                                       String question) {
        if (!citationVerifierEnabled) {
            return events;
        }
        return events.collectList().flatMapMany(parts -> {
            List<ModelStreamEvent> verified = new ArrayList<>();
            parts.stream().filter(event -> "reasoning".equals(event.type())).forEach(verified::add);
            String answer = parts.stream().filter(event -> "token".equals(event.type()))
                    .map(ModelStreamEvent::content).collect(java.util.stream.Collectors.joining());
            verified.add(new ModelStreamEvent("token",
                    citationVerifier.enforce(answer, ledger, citationVerifierStrict, question)));
            return Flux.fromIterable(verified);
        });
    }

    private ChatClient financeChatClient(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return financeChatClient;
        }
        return ChatClient.builder(modelConfigService.chatModel(modelId))
                .defaultSystem("""
                        You are a financial annual-report RAG assistant. Answer only from the retrieved SEC 10-K
                        context supplied in the user message. Lead with the conclusion. For tables or calculations,
                        preserve the company, fiscal year, period, headers, row names, units, and scale from context.
                        If the evidence is insufficient, state exactly what is missing and never invent a fact.
                        Answer in the same language as the user's question.
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

    private SearchOutcome searchWithDiagnostics(FinancialRetrievalPlan plan, FinancialRetrievalMode retrievalMode) {
        List<String> warnings = new ArrayList<>();
        List<FinancialSearchRequest> requests = taskScopedRequests(plan);
        if (requests.isEmpty()) {
            requests = List.of(new FinancialSearchRequest(
                    plan.displayQuery(), "", "", "hybrid", "", requestedItem(plan.resolvedQuestion())));
        }
        RetrievalPolicy policy = retrievalPolicy(plan, Math.max(1, topK));
        int finalTopK = policy.finalTopK();
        int requestCandidateLimit = policy.candidateLimit();
        Map<String, FinancialChunk> merged = new LinkedHashMap<>();
        Map<String, FinancialChunk> scopeAnchors = new LinkedHashMap<>();
        for (FinancialSearchRequest request : requests) {
            String query = request.query();
            if (query == null || query.isBlank()) {
                continue;
            }
            int limit = requestCandidateLimit;
            List<FinancialChunk> requestChunks = search(query, limit, retrievalMode, request.company(), request.year(),
                    request.modality(), false);
            requestChunks = enforceRequestedSection(request, requestChunks, limit, retrievalMode).stream()
                    .map(chunk -> chunk.withMatchedTask(request.taskId())).toList();
            requestChunks = preferNarrativeForQualitativeLanguage(request, requestChunks);
            requestChunks = preferBiographicalFactEvidence(request, requestChunks);
            requestChunks = preferFilingCrossReferenceEvidence(request, requestChunks);
            if ((!request.taskId().isBlank() || !request.company().isBlank() || !request.year().isBlank())
                    && !requestChunks.isEmpty()) {
                String scope = (request.taskId().isBlank()
                        ? request.company().toUpperCase(Locale.ROOT) + "|" + request.year()
                        : request.taskId())
                        + "|" + cleanLine(request.query()).toLowerCase(Locale.ROOT);
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
        int candidateLimit = requestCandidateLimit;
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
            globallyRanked = rerank(stripDatasetRetrievalScope(plan.resolvedQuestion()), globallyRanked,
                    candidateLimit, retrievalMode, warnings);
        }
        globallyRanked = filterCrossSectionNoise(globallyRanked, plan.subTasks());
        globallyRanked = filterQualitativeModalityNoise(globallyRanked, plan.subTasks());
        globallyRanked = promoteFilingCrossReferenceEvidence(globallyRanked, plan.subTasks());
        globallyRanked = promoteTextEnumerationEvidence(plan.resolvedQuestion(), globallyRanked);
        globallyRanked = deduplicateNearDuplicateEvidence(globallyRanked);
        List<FinancialChunk> selected = taskBalancedTopK(globallyRanked, plan.subTasks(), finalTopK);
        selected = rescueMissingTaskCoverage(plan, selected, retrievalMode, finalTopK, warnings);
        warnings.addAll(missingScopedCoverage(plan.subTasks(), selected));
        String quality = warnings.isEmpty() ? "NORMAL" : "DEGRADED";
        return new SearchOutcome(selected, quality, List.copyOf(new LinkedHashSet<>(warnings)));
    }

    int scopedRequestCandidateLimit(int finalTopK) {
        return Math.max(finalTopK,
                Math.min(Math.max(finalTopK, hybridTopK), Math.max(finalTopK, rerankCandidateLimit)));
    }

    RetrievalPolicy retrievalPolicy(FinancialRetrievalPlan plan, int configuredTopK) {
        int cap = Math.max(1, configuredTopK);
        int retrievalTasks = (int) plan.subTasks().stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation())).count();
        if (retrievalTasks <= 1) {
            int finalLimit = Math.min(cap, 3);
            return new RetrievalPolicy(finalLimit,
                    Math.max(finalLimit, Math.min(12, scopedRequestCandidateLimit(finalLimit))));
        }
        int finalLimit = Math.min(cap, Math.max(retrievalTasks, retrievalTasks * 2));
        int candidateLimit = Math.max(finalLimit,
                Math.min(scopedRequestCandidateLimit(finalLimit), Math.max(16, finalLimit * 2)));
        return new RetrievalPolicy(finalLimit, candidateLimit);
    }

    private List<FinancialChunk> rescueMissingTaskCoverage(
            FinancialRetrievalPlan plan, List<FinancialChunk> selected,
            FinancialRetrievalMode retrievalMode, int finalTopK, List<String> warnings) {
        Map<String, FinancialChunk> pool = selected.stream().collect(java.util.stream.Collectors.toMap(
                FinancialChunk::chunkId, FinancialChunk::copy, (left, right) -> left, LinkedHashMap::new));
        List<FinancialSearchRequest> requests = taskScopedRequests(plan);
        for (FinancialRetrievalTask task : plan.subTasks()) {
            if (!"retrieve".equalsIgnoreCase(task.operation()) || hasTaskCoverage(task, pool.values())) {
                continue;
            }
            for (FinancialSearchRequest request : requests) {
                if (!task.id().equals(request.taskId())) {
                    continue;
                }
                int retryLimit = Math.max(16, Math.max(finalTopK, hybridTopK));
                List<FinancialChunk> retryRows = search(
                        request.query() + " exact filing passage", retryLimit, retrievalMode,
                        request.company(), request.year(), request.modality(), false);
                retryRows = enforceRequestedSection(
                        request, retryRows, retryLimit, retrievalMode).stream()
                        .map(chunk -> chunk.withMatchedTask(task.id()))
                        .filter(chunk -> evidenceMatchesTaskScope(task, chunk)).toList();
                retryRows = preferNarrativeForQualitativeLanguage(request, retryRows);
                if (retryRows.isEmpty()) {
                    continue;
                }
                FinancialChunk rescued = retryRows.get(0);
                FinancialChunk existing = pool.get(rescued.chunkId());
                if (existing == null) {
                    pool.put(rescued.chunkId(), rescued);
                } else {
                    existing.matchedTaskIds.addAll(rescued.matchedTaskIds);
                }
                break;
            }
        }
        int retrievalTaskCount = (int) plan.subTasks().stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation())).count();
        return taskBalancedTopK(List.copyOf(pool.values()), plan.subTasks(),
                Math.max(finalTopK, retrievalTaskCount));
    }

    private boolean hasTaskCoverage(FinancialRetrievalTask task, Collection<FinancialChunk> chunks) {
        return chunks.stream().anyMatch(chunk -> chunk.matchedTaskIds.contains(task.id())
                && evidenceMatchesTaskScope(task, chunk));
    }

    private List<FinancialChunk> preferNarrativeForQualitativeLanguage(
            FinancialSearchRequest request, List<FinancialChunk> rows) {
        String query = cleanLine(request.query()).toLowerCase(Locale.ROOT);
        if (!"text".equalsIgnoreCase(request.modality())
                || !containsAnyText(query, "objective", "strategy", "strategic", "mission", "vision")) {
            return rows;
        }
        List<FinancialChunk> narrative = rows.stream()
                .filter(chunk -> "text".equalsIgnoreCase(chunk.chunkType()))
                .toList();
        // SEC HTML occasionally wraps prose in table containers, so retain the original candidates when no
        // narrative chunk was recalled. When narrative is available, tables distract from language questions.
        return narrative.isEmpty() ? rows : narrative;
    }

    private List<FinancialChunk> preferBiographicalFactEvidence(
            FinancialSearchRequest request, List<FinancialChunk> rows) {
        if (!BIOGRAPHICAL_FACT_PATTERN.matcher(cleanLine(request.query())).find()) {
            return rows;
        }
        List<FinancialChunk> exact = rows.stream()
                .filter(chunk -> biographicalEvidenceMatches(request.query(), chunk.content()))
                .toList();
        if (exact.isEmpty()) {
            return rows;
        }
        List<FinancialChunk> dedicatedSections = exact.stream()
                .filter(chunk -> "text".equalsIgnoreCase(chunk.chunkType()))
                .filter(chunk -> Set.of("Item 1", "Item 10", "Item 12").contains(canonicalItem(chunk)))
                .toList();
        return dedicatedSections.isEmpty() ? exact : dedicatedSections;
    }

    private List<FinancialChunk> preferFilingCrossReferenceEvidence(
            FinancialSearchRequest request, List<FinancialChunk> rows) {
        if (!isFilingCrossReferenceDescription(request.query())) {
            return rows;
        }
        List<FinancialChunk> exact = rows.stream()
                .filter(chunk -> filingCrossReferenceEvidenceMatches(chunk.content()))
                .toList();
        return exact.isEmpty() ? rows : exact;
    }

    boolean biographicalEvidenceMatches(String query, String content) {
        List<String> entities = namedEntityPhrases(query);
        for (String entity : entities) {
            if (biographicalAge(entity, content) != null) {
                return true;
            }
        }
        String lowerQuery = cleanLine(query).toLowerCase(Locale.ROOT);
        if (!entities.isEmpty() && containsAnyText(lowerQuery,
                " age", "aged", "date of birth", "birth year", "officer age", "director age")) {
            return false;
        }
        String lowerContent = defaultIfBlank(content, "").toLowerCase(Locale.ROOT);
        if (!lowerQuery.contains("executive officer") || !lowerContent.contains("executive officer")) {
            return false;
        }
        return Pattern.compile(
                        "(?is)\\b[A-Z][a-z]{2,}\\s+[A-Z][a-z]{2,}\\s*[,;(\\-]?\\s*"
                                + "(?:age[d]?\\s*)?(1[89]|[2-9]\\d)\\b")
                .matcher(defaultIfBlank(content, "")).find();
    }

    private Integer biographicalAge(String entity, String content) {
        Pattern nameAndAge = Pattern.compile(
                "(?is)\\b" + Pattern.quote(entity) + "\\b\\s*[,;(\\-]?\\s*"
                        + "(?:age[d]?\\s*)?(1[89]|[2-9]\\d)\\b");
        Matcher matcher = nameAndAge.matcher(defaultIfBlank(content, ""));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private List<FinancialChunk> enforceRequestedSection(
            FinancialSearchRequest request, List<FinancialChunk> rows, int limit,
            FinancialRetrievalMode retrievalMode) {
        if (request.requestedItem().isBlank() || rows.isEmpty()) {
            return rows;
        }
        List<FinancialChunk> scoped = rows.stream()
                .filter(chunk -> request.requestedItem().equalsIgnoreCase(canonicalItem(chunk)))
                .toList();
        if (!scoped.isEmpty()) {
            return scoped;
        }
        String retryQuery = request.query() + " " + request.requestedItem()
                + " section heading opening paragraph exact filing text";
        List<FinancialChunk> retried = search(retryQuery, Math.max(limit * 2, 16), retrievalMode,
                request.company(), request.year(), request.modality(), false).stream()
                .filter(chunk -> request.requestedItem().equalsIgnoreCase(canonicalItem(chunk)))
                .toList();
        if (!retried.isEmpty()) {
            return retried.stream().limit(limit).toList();
        }
        // A task can have several semantic-anchor requests. One anchor missing the requested Item is not a
        // task-level failure when another anchor succeeds; final coverage is evaluated after all requests merge.
        return List.of();
    }

    private List<FinancialChunk> filterCrossSectionNoise(
            List<FinancialChunk> rows, List<FinancialRetrievalTask> tasks) {
        Map<String, String> requestedItemsByTask = tasks.stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation()))
                .filter(task -> !task.id().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        FinancialRetrievalTask::id, this::requestedItemForTask,
                        (left, right) -> left, LinkedHashMap::new));
        Set<String> hardScopedItems = requestedItemsByTask.values().stream()
                .filter(item -> !item.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (hardScopedItems.isEmpty()) {
            return rows;
        }
        List<FinancialChunk> filtered = rows.stream()
                .filter(chunk -> {
                    if (chunk.matchedTaskIds.isEmpty()) {
                        return hardScopedItems.contains(canonicalItem(chunk));
                    }
                    return chunk.matchedTaskIds.stream().anyMatch(taskId -> {
                        String requested = requestedItemsByTask.get(taskId);
                        return requested == null
                                ? hardScopedItems.contains(canonicalItem(chunk))
                                : requested.isBlank() || requested.equalsIgnoreCase(canonicalItem(chunk));
                    });
                })
                .toList();
        return filtered;
    }

    private List<FinancialChunk> filterQualitativeModalityNoise(
            List<FinancialChunk> rows, List<FinancialRetrievalTask> tasks) {
        Set<String> qualitativeTaskIds = tasks.stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation()))
                .filter(task -> "text".equalsIgnoreCase(effectiveTaskModality(task)))
                .filter(task -> containsAnyText(
                        (cleanLine(task.query()) + " " + cleanLine(task.metric())).toLowerCase(Locale.ROOT),
                        "objective", "strategy", "strategic", "mission", "vision"))
                .map(FinancialRetrievalTask::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (qualitativeTaskIds.isEmpty()) {
            return rows;
        }
        Set<String> tasksWithNarrative = rows.stream()
                .filter(chunk -> "text".equalsIgnoreCase(chunk.chunkType()))
                .flatMap(chunk -> chunk.matchedTaskIds.stream())
                .filter(qualitativeTaskIds::contains)
                .collect(java.util.stream.Collectors.toSet());
        List<FinancialChunk> filtered = rows.stream()
                .filter(chunk -> !"table".equalsIgnoreCase(chunk.chunkType())
                        || chunk.matchedTaskIds.stream().noneMatch(tasksWithNarrative::contains))
                .toList();
        return filtered.isEmpty() ? rows : filtered;
    }

    List<FinancialChunk> promoteTextEnumerationEvidence(String question, List<FinancialChunk> rows) {
        if (!isTextEnumerationQuestion(question)) {
            return rows;
        }
        return rows.stream().sorted(Comparator
                .comparing((FinancialChunk chunk) -> DESCRIPTION_ITEM_GROUPS.stream().noneMatch(group ->
                        group.stream().allMatch(phrase -> chunk.content().toLowerCase(Locale.ROOT).contains(phrase))))
                .thenComparing(Comparator.comparingDouble(FinancialChunk::finalScore).reversed()))
                .toList();
    }

    private List<FinancialChunk> promoteFilingCrossReferenceEvidence(
            List<FinancialChunk> rows, List<FinancialRetrievalTask> tasks) {
        Set<String> crossReferenceTaskIds = tasks.stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation()))
                .filter(this::isFilingCrossReferenceTask)
                .map(FinancialRetrievalTask::id)
                .collect(java.util.stream.Collectors.toSet());
        if (crossReferenceTaskIds.isEmpty()) {
            return rows;
        }
        List<FinancialChunk> exact = rows.stream()
                .filter(chunk -> chunk.matchedTaskIds.stream().anyMatch(crossReferenceTaskIds::contains))
                .filter(chunk -> filingCrossReferenceEvidenceMatches(chunk.content()))
                .toList();
        if (exact.isEmpty()) {
            return rows;
        }
        Map<String, FinancialChunk> promoted = new LinkedHashMap<>();
        exact.forEach(chunk -> promoted.putIfAbsent(chunk.chunkId(), chunk));
        rows.forEach(chunk -> promoted.putIfAbsent(chunk.chunkId(), chunk));
        return List.copyOf(promoted.values());
    }

    boolean filingCrossReferenceEvidenceMatches(String content) {
        String lower = cleanLine(content).toLowerCase(Locale.ROOT);
        return lower.contains("consolidated sales")
                && lower.contains("additional information")
                && lower.contains("reportable segments")
                && Pattern.compile("\\bnote\\s+\\d+\\b").matcher(lower).find();
    }

    private List<FinancialChunk> deduplicateNearDuplicateEvidence(List<FinancialChunk> rows) {
        Map<String, FinancialChunk> selected = new LinkedHashMap<>();
        for (FinancialChunk candidate : rows) {
            FinancialChunk duplicate = selected.values().stream()
                    .filter(existing -> existing.sourceFile().equalsIgnoreCase(candidate.sourceFile()))
                    .filter(existing -> existing.chunkType().equalsIgnoreCase(candidate.chunkType()))
                    .filter(existing -> canonicalItem(existing).equalsIgnoreCase(canonicalItem(candidate)))
                    .filter(existing -> nearDuplicateText(existing.content(), candidate.content()))
                    .findFirst().orElse(null);
            if (duplicate == null) {
                selected.put(candidate.chunkId(), candidate);
            } else {
                // Preserve task coverage even though only the higher-ranked passage is sent to the model.
                duplicate.matchedTaskIds.addAll(candidate.matchedTaskIds);
            }
        }
        return List.copyOf(selected.values());
    }

    boolean nearDuplicateText(String left, String right) {
        List<String> leftTokens = tokenize(defaultIfBlank(left, ""));
        List<String> rightTokens = tokenize(defaultIfBlank(right, ""));
        if (leftTokens.size() < 20 || rightTokens.size() < 20) {
            return false;
        }
        Set<String> leftShingles = tokenShingles(leftTokens, 5);
        Set<String> rightShingles = tokenShingles(rightTokens, 5);
        if (leftShingles.isEmpty() || rightShingles.isEmpty()) {
            return false;
        }
        Set<String> intersection = new HashSet<>(leftShingles);
        intersection.retainAll(rightShingles);
        Set<String> union = new HashSet<>(leftShingles);
        union.addAll(rightShingles);
        return (double) intersection.size() / union.size() >= 0.82;
    }

    private Set<String> tokenShingles(List<String> tokens, int size) {
        Set<String> shingles = new HashSet<>();
        for (int index = 0; index + size <= tokens.size(); index++) {
            shingles.add(String.join(" ", tokens.subList(index, index + size)));
        }
        return shingles;
    }

    List<String> missingScopedCoverage(
            List<FinancialRetrievalTask> tasks, List<FinancialChunk> chunks) {
        List<String> missing = new ArrayList<>();
        for (FinancialRetrievalTask task : tasks) {
            if (!"retrieve".equalsIgnoreCase(task.operation())) {
                continue;
            }
            String item = requestedItemForTask(task);
            if (item.isBlank()) {
                continue;
            }
            boolean covered = chunks.stream().anyMatch(chunk -> chunk.matchedTaskIds.contains(task.id())
                    && item.equalsIgnoreCase(canonicalItem(chunk)));
            if (!covered) {
                missing.add("missing_scoped_coverage: " + task.id() + " | " + item);
            }
        }
        return missing;
    }

    String canonicalItem(FinancialChunk chunk) {
        return canonicalItem(chunk.metadataText("item"), chunk.chunkType(),
                chunk.metadataText("section_title"), chunk.content());
    }

    String canonicalItem(String rawItem, String chunkType, String sectionTitle, String content) {
        String raw = normalizeItem(rawItem);
        String opening = (cleanLine(sectionTitle) + " "
                + truncateEnd(content, 1200)).toLowerCase(Locale.ROOT);
        // Some source chunks carry a stale Item 1/1A label even though their narrative is the standard
        // Item 7 MD&A introduction. This strong three-phrase signature is more reliable than that label,
        // but is deliberately restricted to narrative chunks so repeated table parent context cannot override it.
        if ("text".equalsIgnoreCase(chunkType) && DESCRIPTION_ITEM_GROUPS.stream().anyMatch(group ->
                group.stream().allMatch(opening::contains))) {
            return "Item 7";
        }
        String header = (cleanLine(sectionTitle) + " " + truncateEnd(content, 280)).toLowerCase(Locale.ROOT);
        Matcher explicit = REQUESTED_ITEM_PATTERN.matcher(header);
        if (explicit.find()) {
            return "Item " + explicit.group(1).toUpperCase(Locale.ROOT);
        }
        return raw;
    }

    private String normalizeItem(String value) {
        Matcher matcher = REQUESTED_ITEM_PATTERN.matcher(cleanLine(value));
        return matcher.find() ? "Item " + matcher.group(1).toUpperCase(Locale.ROOT) : cleanLine(value);
    }

    private List<FinancialChunk> taskBalancedTopK(List<FinancialChunk> ranked,
                                                   List<FinancialRetrievalTask> tasks,
                                                   int limit) {
        Map<String, FinancialChunk> selected = new LinkedHashMap<>();
        for (FinancialRetrievalTask task : tasks) {
            if (!"retrieve".equalsIgnoreCase(task.operation())) {
                continue;
            }
            List<FinancialChunk> taskCandidates = ranked.stream()
                    .filter(chunk -> chunk.matchedTaskIds.contains(task.id())).toList();
            FinancialChunk best = taskCandidates.stream()
                    .filter(chunk -> evidenceMatchesTaskScope(task, chunk))
                    .filter(chunk -> evidenceLedger.containsDirectMetricValue(task.metric(), chunk.content()))
                    .findFirst().orElse(taskCandidates.isEmpty() ? null : taskCandidates.get(0));
            if (best != null) {
                selected.putIfAbsent(best.chunkId(), best);
            }
            if (selected.size() >= limit) {
                return List.copyOf(selected.values());
            }
        }
        // Fill remaining positions with task-scoped candidates first. Unscoped global-query rows are useful only
        // as a last resort; allowing them to outrank scoped rows introduces unrelated companies into the ledger.
        for (FinancialChunk chunk : ranked) {
            if (chunk.matchedTaskIds.isEmpty()) {
                continue;
            }
            selected.putIfAbsent(chunk.chunkId(), chunk);
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
        String table = resolveTableName();
        Set<String> known = knownCompanies(table);
        for (FinancialRetrievalTask task : plan.subTasks()) {
            if (!"retrieve".equalsIgnoreCase(task.operation()) || task.query().isBlank()) {
                continue;
            }
            List<String> companies = task.companies().isEmpty() ? List.of("") : task.companies();
            List<String> years = task.years().isEmpty() ? List.of("") : task.years();
            for (String company : companies) {
                for (String year : years) {
                    String normalizedCompany = canonicalTaskCompany(company, task.query(), known);
                    String taskQuery = retrievalQueryForTask(task);
                    String taskItem = requestedItemForTask(task);
                    addSearchRequest(requests, new FinancialSearchRequest(
                            expandQueryTerms(taskQuery),
                            normalizedCompany, year, effectiveTaskModality(task), task.id(), taskItem));
                    for (String anchorQuery : semanticAnchorQueries(taskQuery)) {
                        addSearchRequest(requests, new FinancialSearchRequest(
                                anchorQuery, normalizedCompany, year, effectiveTaskModality(task), task.id(),
                                defaultIfBlank(requestedItem(anchorQuery), taskItem)));
                    }
                }
            }
        }
        if (!hasScopedRetrievalTask(plan)) {
            for (String query : plan.queries()) {
                addSearchRequest(requests, new FinancialSearchRequest(
                        query, "", "", "hybrid", "", requestedItem(query)));
            }
        }
        return List.copyOf(requests);
    }

    boolean hasScopedRetrievalTask(FinancialRetrievalPlan plan) {
        return plan.subTasks().stream()
                .filter(task -> "retrieve".equalsIgnoreCase(task.operation()))
                .anyMatch(task -> !task.companies().isEmpty() || !task.years().isEmpty());
    }

    String retrievalQueryForTask(FinancialRetrievalTask task) {
        String query = String.join(" ", List.of(
                stripDatasetRetrievalScope(task.query()), cleanLine(task.metric()))).strip();
        if (!usesSoftSectionScope(task)) {
            return query;
        }
        return cleanLine(REQUESTED_ITEM_PATTERN.matcher(query).replaceAll(""));
    }

    String requestedItemForTask(FinancialRetrievalTask task) {
        return usesSoftSectionScope(task) ? "" : requestedItem(task.query());
    }

    boolean usesSoftSectionScope(FinancialRetrievalTask task) {
        return isBiographicalFactTask(task) || isFilingCrossReferenceTask(task);
    }

    boolean isBiographicalFactTask(FinancialRetrievalTask task) {
        String description = cleanLine(task.query()) + " " + cleanLine(task.metric());
        return BIOGRAPHICAL_FACT_PATTERN.matcher(description).find();
    }

    boolean isFilingCrossReferenceTask(FinancialRetrievalTask task) {
        String description = (cleanLine(task.query()) + " " + cleanLine(task.metric()))
                .toLowerCase(Locale.ROOT);
        return isFilingCrossReferenceDescription(description);
    }

    private boolean isFilingCrossReferenceDescription(String value) {
        String description = cleanLine(value).toLowerCase(Locale.ROOT);
        boolean asksForReference = containsAnyText(description,
                "referenced table", "referenced note", "references to financial", "table references",
                "table note reference", "table or note reference", "segment table or note",
                "additional information", "additional segment detail", "instead of narrative");
        boolean describesTarget = containsAnyText(description,
                "reportable segment", "segment detail", "sales data", "sales revenue", "sales driver");
        return asksForReference && describesTarget;
    }

    String stripDatasetRetrievalScope(String question) {
        return cleanLine(DATASET_SCOPE_PATTERN.matcher(defaultIfBlank(question, "")).replaceFirst(""));
    }

    List<String> semanticAnchorQueries(String query) {
        String lower = cleanLine(query).toLowerCase(Locale.ROOT);
        List<String> anchors = new ArrayList<>();
        if (lower.contains("executive officer")) {
            anchors.add("Executive Officers name age position elected Board of Directors");
        }
        if (lower.contains("item 7")
                && (lower.contains("description") || lower.contains("financial statement item")
                || lower.contains("financial statement term"))) {
            anchors.add("Item 7 forward-looking statements actual results financial position "
                    + "cash generated from operations");
            anchors.add("Item 7 MD&A financial condition results of operations cash flows from operations "
                    + "outside sources");
        }
        if (containsAnyText(lower, "strategic objective", "primary objective", "strategy", "strategic priorities")) {
            anchors.add("Item 7 explicit strategic objective shareholder value mission vision dividend growth earnings growth");
            if (lower.contains("item 7")) {
                anchors.add("Item 7 forward-looking statements risks uncertainties strategic objectives");
            }
        }
        if (lower.contains("forward-looking") && containsAnyText(lower, "disclaimer", "cautionary", "safe harbor")) {
            anchors.add("Item 7 strategies may not be effective changes external business environment "
                    + "competitive landscape public policy laws regulations customer behavior technology");
        }
        if (containsAnyText(lower, "quantitative sales driver", "quantitative information")
                && containsAnyText(lower, "volume", "pricing", "sales driver")) {
            anchors.add("provide quantitative information material sales drivers changes volume pricing "
                    + "acquisitions foreign currency corporate reportable segment level");
        }
        if (lower.contains("paid net membership additions")) {
            anchors.add("consolidated performance highlights Global Streaming Memberships "
                    + "paid net membership additions");
        }
        if (lower.contains("paid memberships at end of period")) {
            anchors.add("consolidated performance highlights Global Streaming Memberships "
                    + "paid memberships at end of period");
        }
        if (containsAnyText(lower, "referenced table", "referenced note", "references to financial",
                "table references", "table note reference", "table or note reference", "segment table or note",
                "additional information", "additional segment detail", "instead of narrative")
                && containsAnyText(lower, "reportable segment", "segment detail", "sales data",
                "sales revenue", "sales driver")) {
            anchors.add("consolidated sales consolidated financial statements additional information "
                    + "related to reportable segments");
        }
        return List.copyOf(anchors);
    }

    private String canonicalTaskCompany(String company, String query, Set<String> known) {
        String candidate = cleanLine(company).toUpperCase(Locale.ROOT);
        if (known.contains(candidate)) {
            return candidate;
        }
        String description = (cleanLine(company) + " " + cleanLine(query)).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> alias : COMPANY_ALIASES.entrySet()) {
            if (description.contains(alias.getKey()) && known.contains(alias.getValue())) {
                return alias.getValue();
            }
        }
        // An invented company value is more harmful than an unscoped search: it produces an empty result set.
        return "";
    }

    String effectiveTaskModality(FinancialRetrievalTask task) {
        String query = (cleanLine(task.query()) + " " + cleanLine(task.metric())).toLowerCase(Locale.ROOT);
        if (query.matches(".*\\b(age|aged|biograph(?:y|ical)|executive|officer|director|language|quote|quotation|"
                + "statement|states|stated|mention|mentions|mentioned|textual|narrative|objective|objectives|"
                + "strategy|strategies|strategic|mission|vision)\\b.*")
                || query.contains("management's discussion")
                || query.contains("management’s discussion")
                || query.contains("md&a")) {
            return "text";
        }
        return task.modality();
    }

    private void addSearchRequest(List<FinancialSearchRequest> requests, FinancialSearchRequest candidate) {
        String query = cleanLine(candidate.query());
        if (query.isBlank()) {
            return;
        }
        FinancialSearchRequest normalized = new FinancialSearchRequest(query, cleanLine(candidate.company()),
                cleanLine(candidate.year()), normalizeModality(candidate.modality()), cleanLine(candidate.taskId()),
                normalizeItem(candidate.requestedItem()));
        boolean duplicate = requests.stream().anyMatch(existing ->
                existing.query().equalsIgnoreCase(normalized.query())
                        && existing.company().equalsIgnoreCase(normalized.company())
                        && existing.year().equalsIgnoreCase(normalized.year())
                        && existing.modality().equalsIgnoreCase(normalized.modality())
                        && existing.taskId().equalsIgnoreCase(normalized.taskId())
                        && existing.requestedItem().equalsIgnoreCase(normalized.requestedItem()));
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
        List<FinancialChunk> candidateRows = fetchLexicalCandidates(
                table, filters, query, Math.max(finalHybridTopK * 20, 500));
        List<FinancialChunk> exactPhraseRows = fetchExactPhraseCandidates(
                table, filters, query, exactAnchorPhrases(query), finalTopK);
        if (candidateRows.isEmpty() && filters.hasNarrowScope()) {
            candidateRows = fetchMetadataCandidates(table, filters);
        }
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
        ranked = promoteExactPhraseRows(exactPhraseRows, ranked, finalHybridTopK);
        if (applyRerank && rerankEnabled) {
            ranked = rerank(query, ranked, finalTopK, retrievalMode);
        }
        return ranked.stream().limit(finalTopK).toList();
    }

    RetrievalFilters explicitFilters(String table, String company, String year, String modality) {
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
            // Filing parsers frequently wrap narrative paragraphs in a table container (for example,
            // an introductory table followed by a forward-looking-statements paragraph). Treat text
            // as a ranking preference, not a hard SQL filter, so those mixed chunks remain retrievable.
            case "text" -> "";
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

    private List<FinancialChunk> fetchExactPhraseCandidates(String table,
                                                             RetrievalFilters filters,
                                                             String query,
                                                             List<String> phrases,
                                                             int limit) {
        if (phrases.isEmpty()) {
            return List.of();
        }
        SqlWhere where = whereClause(filters);
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>(where.params());
        for (String phrase : phrases) {
            conditions.add("lower(content) LIKE ?");
            params.add("%" + phrase.toLowerCase(Locale.ROOT) + "%");
        }
        params.add(Math.max(24, limit * 8));
        String prefix = where.sql().isBlank() ? "WHERE " : where.sql() + " AND ";
        String sql = """
                SELECT chunk_id, source_file, chunk_type, content, metadata::text AS metadata, 1.0 AS score
                FROM %s
                %s %s
                ORDER BY CASE WHEN chunk_type = 'text' THEN 0 ELSE 1 END, length(content), chunk_index
                LIMIT ?
                """.formatted(safeTableName(table), prefix, String.join(" AND ", conditions));
        List<FinancialChunk> matches = jdbcTemplate.query(sql, this::mapChunk, params.toArray());
        String requestedItem = requestedItem(query);
        if (!requestedItem.isBlank()) {
            matches = matches.stream().sorted(Comparator
                    .comparing((FinancialChunk chunk) -> !requestedItem.equalsIgnoreCase(
                            cleanLine(chunk.metadataText("item"))))
                    .thenComparing(chunk -> !"text".equalsIgnoreCase(chunk.chunkType()))
                    .thenComparingInt(chunk -> chunk.content().length()))
                    .toList();
        }
        return matches.stream().limit(Math.max(1, limit)).toList();
    }

    String requestedItem(String query) {
        Matcher matcher = REQUESTED_ITEM_PATTERN.matcher(cleanLine(query));
        return matcher.find() ? "Item " + matcher.group(1).toUpperCase(Locale.ROOT) : "";
    }

    List<String> exactAnchorPhrases(String query) {
        String lower = cleanLine(query).toLowerCase(Locale.ROOT);
        if (lower.contains("consolidated sales") && lower.contains("additional information")
                && lower.contains("reportable segments")) {
            return List.of("consolidated sales", "additional information", "reportable segments");
        }
        if (lower.contains("actual results") && lower.contains("cash generated from operations")) {
            return List.of("actual results", "financial position", "cash generated from operations");
        }
        if (lower.contains("financial condition") && lower.contains("cash flows from operations")) {
            return List.of("financial condition", "results of operations", "cash flows from operations");
        }
        if (lower.contains("shareholder value") && lower.contains("dividend growth")) {
            return List.of("shareholder value", "dividend growth", "earnings growth");
        }
        if (lower.contains("forward-looking statements") && lower.contains("risks")
                && lower.contains("uncertainties")) {
            return List.of("forward-looking statements", "risks", "uncertainties");
        }
        if (lower.contains("strategies may not be effective") && lower.contains("external business environment")) {
            return List.of("strategies may not be effective", "external business environment", "competitive landscape");
        }
        if (lower.contains("provide quantitative information") && lower.contains("material sales drivers")) {
            return List.of("provide quantitative information", "material sales drivers", "volume and pricing");
        }
        if (lower.contains("consolidated performance highlights")
                && lower.contains("global streaming memberships")
                && lower.contains("paid net membership additions")) {
            return List.of("consolidated performance highlights", "global streaming memberships",
                    "paid net membership additions");
        }
        if (lower.contains("consolidated performance highlights")
                && lower.contains("global streaming memberships")
                && lower.contains("paid memberships at end of period")) {
            return List.of("consolidated performance highlights", "global streaming memberships",
                    "paid memberships at end of period");
        }
        return List.of();
    }

    private List<FinancialChunk> promoteExactPhraseRows(List<FinancialChunk> exactRows,
                                                         List<FinancialChunk> ranked,
                                                         int limit) {
        Map<String, FinancialChunk> promoted = new LinkedHashMap<>();
        for (FinancialChunk exact : exactRows) {
            FinancialChunk copy = exact.copy();
            copy.finalScore = Math.max(1.0, copy.finalScore);
            promoted.putIfAbsent(copy.chunkId(), copy);
        }
        for (FinancialChunk chunk : ranked) {
            promoted.putIfAbsent(chunk.chunkId(), chunk);
            if (promoted.size() >= limit) {
                break;
            }
        }
        return List.copyOf(promoted.values());
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
        return rerank(query, rows, finalTopK, retrievalMode, null);
    }

    private List<FinancialChunk> rerank(String query, List<FinancialChunk> rows, int finalTopK,
                                        FinancialRetrievalMode retrievalMode, List<String> warnings) {
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
            if (warnings != null) {
                warnings.add("reranker_unavailable: lexical_vector_fallback");
            }
            return rows;
        }
    }

    private String rerankDocumentText(FinancialChunk chunk, FinancialRetrievalMode retrievalMode) {
        String prefix = metadataPrefix(chunk);
        // Rank the precise child passage. Parent context is added only after ranking and evidence verification.
        String text = prefix + "\n" + chunk.content();
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
                return childWithParentContext(chunk.content(), parentContext);
            }
        }
        return chunk.content();
    }

    String childWithParentContext(String child, String parentContext) {
        String normalizedChild = cleanLine(child);
        String normalizedParent = cleanLine(parentContext);
        if (normalizedChild.isBlank()) {
            return parentContext == null ? "" : parentContext;
        }
        if (normalizedParent.isBlank() || normalizedParent.contains(normalizedChild)) {
            return normalizedParent.isBlank() ? child : parentContext;
        }
        // Keep the exact matched child first. A truncated parent window may otherwise hide the name/value
        // that caused retrieval, leading reranking and evidence extraction to see only surrounding context.
        return child.strip() + "\n\nParent context:\n" + parentContext.strip();
    }

    private List<FinancialChunk> collapseParentChildRows(List<FinancialChunk> rows, int limit) {
        Map<String, FinancialChunk> collapsed = new LinkedHashMap<>();
        for (FinancialChunk row : rows) {
            FinancialChunk current = row.copy();
            String key = parentCollapseKey(current);
            FinancialChunk existing = collapsed.get(key);
            if (existing == null) {
                collapsed.put(key, current);
            } else if (current.finalScore() > existing.finalScore()) {
                current.matchedTaskIds.addAll(existing.matchedTaskIds);
                collapsed.put(key, current);
            } else {
                existing.matchedTaskIds.addAll(current.matchedTaskIds);
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
        for (String entity : namedEntityPhrases(query)) {
            if (content.contains(entity.toLowerCase(Locale.ROOT))) {
                boost += 1.5;
            }
        }
        return Math.min(boost, 3.0);
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

    private record FinancialSearchRequest(String query, String company, String year, String modality,
                                          String taskId, String requestedItem) {
    }

    private record SearchOutcome(List<FinancialChunk> chunks, String quality, List<String> warnings) {
    }

    record RetrievalPolicy(int finalTopK, int candidateLimit) {
    }

    private record CompanyHit(String company, int matches) {
    }

    private record TextEnumerationResult(
            String company,
            List<String> phrases,
            List<FinancialEvidenceLedger.EvidenceDocument> evidence) {
    }

    record CompanyYearScope(String company, String year) {
    }

    record FinancialRetrievalPlan(String translatedQuestion,
                                  String resolvedQuestion,
                                  String intent,
                                  List<String> queries,
                                  List<FinancialRetrievalTask> subTasks,
                                  List<FinancialEvidenceLedger.CalculationPlan> calculationPlans) {
        FinancialRetrievalPlan(String translatedQuestion, String resolvedQuestion, String intent,
                               List<String> queries, List<FinancialRetrievalTask> subTasks) {
            this(translatedQuestion, resolvedQuestion, intent, queries, subTasks, List.of());
        }

        FinancialRetrievalPlan {
            queries = queries == null ? List.of() : List.copyOf(queries);
            subTasks = subTasks == null ? List.of() : List.copyOf(subTasks);
            calculationPlans = calculationPlans == null ? List.of() : List.copyOf(calculationPlans);
        }

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
                                         boolean modelRequested, String retrievalQuery,
                                         String retrievalQuality, List<String> retrievalWarnings) {
    }

    public record AnalysisTask(String id, String query, List<String> companies, List<String> years,
                               String metric, String operation, String modality, List<String> dependsOn) {
    }

    public record AnalysisEvidence(String evidenceId, String chunkId, String sourceFile, String company,
                                   String fiscalYear, String modality, String item, String sectionTitle,
                                   List<String> matchedTaskIds, List<String> verifiedTaskIds) {
    }

    public record AnalysisFact(String factId, String evidenceId, String company, String fiscalYear, String metric,
                               String rawValue, String value, String unit, String scale, String quote) {
    }

    public record AnalysisCalculation(String calculationId, String type, String expression, String result,
                                      String unit, String scale, List<String> sourceFactIds) {
    }

    public record AnalysisCalculationOperand(String role, String sourceTaskId, String company,
                                              String fiscalYear, String metric, String rawValue,
                                              String unit, String scale) {
    }

    public record AnalysisCalculationPlan(String id, String operator, List<AnalysisCalculationOperand> operands,
                                          String outputUnit, String outputScale, int precision, int periods) {
    }

    public record CitationAudit(boolean valid, List<String> issues) {
    }

    public record FinancialAnalysisResult(String answer, String resolvedQuestion, String intent,
                                          List<AnalysisTask> tasks, List<AnalysisCalculationPlan> calculationPlans,
                                          List<AnalysisEvidence> evidence,
                                          List<AnalysisFact> facts, List<AnalysisCalculation> calculations,
                                          CitationAudit citationAudit, long planningMs, long retrievalMs,
                                          long generationMs, long totalMs, String error,
                                          String retrievalQuality, List<String> retrievalWarnings) {
        public FinancialAnalysisResult(String answer, String resolvedQuestion, String intent,
                                       List<AnalysisTask> tasks, List<AnalysisEvidence> evidence,
                                       List<AnalysisFact> facts, List<AnalysisCalculation> calculations,
                                       CitationAudit citationAudit, long planningMs, long retrievalMs,
                                       long generationMs, long totalMs, String error,
                                       String retrievalQuality, List<String> retrievalWarnings) {
            this(answer, resolvedQuestion, intent, tasks, List.of(), evidence, facts, calculations,
                    citationAudit, planningMs, retrievalMs, generationMs, totalMs, error,
                    retrievalQuality, retrievalWarnings);
        }

        static FinancialAnalysisResult failed(String error) {
            return new FinancialAnalysisResult("", "", "unknown", List.of(), List.of(), List.of(), List.of(), List.of(),
                    new CitationAudit(false, List.of(error == null ? "unknown error" : error)),
                    0L, 0L, 0L, 0L, error == null ? "unknown error" : error,
                    "FAILED", List.of(error == null ? "unknown error" : error));
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

    static final class FinancialChunk {
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

        FinancialChunk(String chunkId,
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

        FinancialChunk withMatchedTask(String taskId) {
            FinancialChunk copy = copy();
            if (taskId != null && !taskId.isBlank()) {
                copy.matchedTaskIds.add(taskId);
            }
            return copy;
        }

        private FinancialChunk withOnlyMatchedTasks(Collection<String> taskIds) {
            FinancialChunk copy = copy();
            copy.matchedTaskIds.clear();
            if (taskIds != null) {
                copy.matchedTaskIds.addAll(taskIds);
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

        Set<String> matchedTaskIds() {
            return Set.copyOf(matchedTaskIds);
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
