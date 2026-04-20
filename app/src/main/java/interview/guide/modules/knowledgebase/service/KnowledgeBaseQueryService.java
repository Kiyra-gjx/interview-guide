package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryDebugInfo;
import interview.guide.modules.knowledgebase.model.QueryDebugResponse;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final Pattern PRECISION_TOKEN_PATTERN = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9][A-Za-z0-9_-]{1,31}(?![A-Za-z0-9_-])");
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int DEBUG_PREVIEW_CHARS = 180;

    private final ChatClient chatClient;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    /**
     * 初始化知识库问答所需的提示词模板、检索服务和动态检索参数。
     */
    public KnowledgeBaseQueryService(
            ChatClient.Builder chatClientBuilder,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            @Value("classpath:prompts/knowledgebase-query-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/knowledgebase-query-user.st") Resource userPromptResource,
            @Value("classpath:prompts/knowledgebase-query-rewrite.st") Resource rewritePromptResource,
            @Value("${app.ai.rag.rewrite.enabled:true}") boolean rewriteEnabled,
            @Value("${app.ai.rag.search.short-query-length:4}") int shortQueryLength,
            @Value("${app.ai.rag.search.topk-short:20}") int topkShort,
            @Value("${app.ai.rag.search.topk-medium:12}") int topkMedium,
            @Value("${app.ai.rag.search.topk-long:8}") int topkLong,
            @Value("${app.ai.rag.search.min-score-short:0.18}") double minScoreShort,
            @Value("${app.ai.rag.search.min-score-default:0.28}") double minScoreDefault) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.rewritePromptTemplate = new PromptTemplate(rewritePromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.rewriteEnabled = rewriteEnabled;
        this.shortQueryLength = shortQueryLength;
        this.topkShort = topkShort;
        this.topkMedium = topkMedium;
        this.topkLong = topkLong;
        this.minScoreShort = minScoreShort;
        this.minScoreDefault = minScoreDefault;
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        return executeQuery(knowledgeBaseIds, question, false).answer();
    }

    /**
     * 执行一次完整的知识库问答流程，并按需返回检索调试信息。
     */
    private QueryExecutionResult executeQuery(List<Long> knowledgeBaseIds, String question, boolean includeDebugInfo) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            // 空请求直接返回固定兜底答案，并按需构造空的调试信息。
            QueryDebugInfo debugInfo = includeDebugInfo
                ? buildQueryDebugInfo(knowledgeBaseIds, null, RetrievalResult.empty(List.of()))
                : null;
            return new QueryExecutionResult(NO_RESULT_RESPONSE, debugInfo);
        }

        // 1. 验证知识库是否存在并更新问题计数（合并数据库操作）
        countService.updateQuestionCounts(knowledgeBaseIds);

        // 2. Query rewrite + 动态参数检索（RAG）
        QueryContext queryContext = buildQueryContext(question);
        RetrievalResult retrievalResult = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
        List<Document> relevantDocs = retrievalResult.docs();

        // 2.1 调试信息和主流程共用同一份检索结果，避免重复检索。
        QueryDebugInfo debugInfo = includeDebugInfo
            ? buildQueryDebugInfo(knowledgeBaseIds, queryContext, retrievalResult)
            : null;

        if (!retrievalResult.effectiveHit()) {
            return new QueryExecutionResult(NO_RESULT_RESPONSE, debugInfo);
        }

        // 3. 构建上下文（合并检索到的文档）
        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

        // 4. 构建提示词
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            // 5. 调用AI生成回答
            // 5.1 只有在检索命中有效时才调用模型，减少无意义生成。
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return new QueryExecutionResult(answer, debugInfo);

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render();
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        QueryExecutionResult result = executeQuery(request.knowledgeBaseIds(), request.question(), false);

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(result.answer(), primaryKbId, kbNamesStr);
    }

    /**
     * 查询知识库并返回带检索调试信息的完整响应
     */
    public QueryDebugResponse queryKnowledgeBaseWithDebug(QueryRequest request) {
        QueryExecutionResult result = executeQuery(request.knowledgeBaseIds(), request.question(), true);

        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryDebugResponse(result.answer(), primaryKbId, kbNamesStr, result.debugInfo());
    }

    /**
     * 流式查询知识库（SSE）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库流式提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            QueryContext queryContext = buildQueryContext(question);
            RetrievalResult retrievalResult = retrieveRelevantDocs(queryContext, knowledgeBaseIds);
            List<Document> relevantDocs = retrievalResult.docs();

            if (!retrievalResult.effectiveHit()) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            logRetrievalDebug(knowledgeBaseIds, queryContext, retrievalResult);

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用 + 探测窗口归一化：既保留流式速度，又避免无信息长文
            Flux<String> responseFlux = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            return normalizeStreamOutput(responseFlux)
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建一次检索所需的上下文，包括归一化问题、rewrite 候选和动态检索参数。
     */
    private QueryContext buildQueryContext(String originalQuestion) {
        // 1. 先归一化问题文本，统一处理空白字符。
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        // 2. 提取术语 token，供 rewrite 决策和命中有效性校验复用。
        List<String> precisionTokens = extractPrecisionTokens(normalizedQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, precisionTokens);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        // 3. 同时保留 rewrite 前后的候选 query，按顺序尝试检索。
        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(
            normalizedQuestion,
            rewrittenQuestion,
            new ArrayList<>(candidates),
            searchParams,
            precisionTokens
        );
    }

    /**
     * 做最基础的问题清洗，避免空指针和首尾空白影响后续流程。
     */
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    /**
     * 按候选 query 顺序检索文档，并在首个有效命中时结束搜索。
     */
    private RetrievalResult retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        List<RetrievalAttempt> attempts = new ArrayList<>();
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            // 1. 使用动态 topK 和 minScore 执行向量检索。
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            // 2. 检索命中后还要做一次精确术语校验，避免弱相关结果进入生成阶段。
            HitEvaluation hitEvaluation = evaluateHit(queryContext.originalQuestion(), queryContext.precisionTokens(), docs);
            attempts.add(new RetrievalAttempt(candidateQuery, docs, hitEvaluation.effectiveHit(), hitEvaluation.rejectionReason()));
            log.info("检索候选 query='{}'，命中 {} 条，有效命中={}, rejectionReason={}",
                candidateQuery, docs.size(), hitEvaluation.effectiveHit(), hitEvaluation.rejectionReason());
            if (hitEvaluation.effectiveHit()) {
                return new RetrievalResult(candidateQuery, docs, true, attempts);
            }
        }
        return RetrievalResult.empty(attempts);
    }

    /**
     * 根据问题长度选择检索参数，让短问题和长问题使用不同的召回策略。
     */
    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

    /**
     * 从问题中抽取术语型 token，用于精确召回约束和 rewrite 保护。
     */
    private List<String> extractPrecisionTokens(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        Set<String> tokens = new LinkedHashSet<>();
        var matcher = PRECISION_TOKEN_PATTERN.matcher(question);
        while (matcher.find()) {
            String token = matcher.group();
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }
        return new ArrayList<>(tokens);
    }

    /**
     * 判断当前问题是否应该跳过 rewrite，避免把本就精确的术语问法改坏。
     */
    private boolean shouldSkipRewrite(String question, List<String> precisionTokens) {
        if (question == null || question.isBlank()) {
            return true;
        }
        return precisionTokens.size() == 1 && question.equals(precisionTokens.getFirst());
    }

    /**
     * 对问题做可控 rewrite，失败时回退到原问题，不阻断主流程。
     */
    private String rewriteQuestion(String question, List<String> precisionTokens) {
        // 1. rewrite 开关关闭或问题为空时直接短路。
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }

        // 2. 单术语精确问法跳过 rewrite，优先保护召回精度。
        if (shouldSkipRewrite(question, precisionTokens)) {
            log.info("Query rewrite 跳过: question='{}', precisionTokens={}", question, precisionTokens);
            return question;
        }

        try {
            // 3. rewrite 只生成候选检索词，不改动原始问题本身。
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = chatClient.prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}'", question, normalized);
            return normalized;
        } catch (Exception e) {
            // 4. rewrite 失败时继续使用原问题，避免辅助能力影响主链路可用性。
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 检索命中不等于可回答。
     * 对术语 / 标识符场景增加一次命中确认，避免把弱相关片段交给模型后生成大段“信息不足说明”。
     */
    private HitEvaluation evaluateHit(String originalQuestion, List<String> precisionTokens, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return new HitEvaluation(false, "no_hits");
        }

        if (precisionTokens == null || precisionTokens.isEmpty()) {
            return new HitEvaluation(true, null);
        }

        List<String> missingTokens = new ArrayList<>();
        for (String precisionToken : precisionTokens) {
            // 逐个检查关键 token 是否至少出现在一条候选文档中。
            if (!containsToken(docs, precisionToken)) {
                missingTokens.add(precisionToken);
            }
        }

        if (missingTokens.isEmpty()) {
            return new HitEvaluation(true, null);
        }

        log.info("术语 query 命中确认失败，视为无有效结果: question='{}', missingTokens={}, docs={}",
            normalizeQuestion(originalQuestion), missingTokens, docs.size());
        return new HitEvaluation(false, "missing_precision_tokens:" + String.join(",", missingTokens));
    }

    /**
     * 判断候选文档中是否包含指定 token，命中判断使用不区分大小写的包含关系。
     */
    private boolean containsToken(List<Document> docs, String token) {
        String loweredToken = token.toLowerCase();
        for (Document doc : docs) {
            String text = doc.getText();
            if (text != null && text.toLowerCase().contains(loweredToken)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗模型回答，把空回答或“无结果”类模板统一收敛成固定兜底文案。
     */
    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    /**
     * 判断当前文本是否属于“未检索到有效信息”的模型回复模板。
     */
    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    // 1. 先缓存前一小段输出，快速识别“无结果”模板。
                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        // 2. 一旦确认是正常回答，就切换到透传模式，保持流式速度。
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        // 3. 如果流提前结束且仍在探测阶段，再做一次统一归一化兜底。
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    /**
     * 构造调试接口需要的检索元信息，便于排查 rewrite、召回和命中判断问题。
     */
    private QueryDebugInfo buildQueryDebugInfo(
        List<Long> knowledgeBaseIds,
        QueryContext queryContext,
        RetrievalResult retrievalResult
    ) {
        List<Long> debugKbIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        if (queryContext == null) {
            return new QueryDebugInfo(
                debugKbIds,
                "",
                "",
                List.of(),
                List.of(),
                null,
                0,
                0,
                0,
                false,
                List.of()
            );
        }

        return new QueryDebugInfo(
            debugKbIds,
            queryContext.originalQuestion(),
            queryContext.rewrittenQuestion(),
            List.copyOf(queryContext.candidateQueries()),
            buildCandidateDebug(retrievalResult.attempts()),
            retrievalResult.retrievalQuery(),
            queryContext.searchParams().topK(),
            queryContext.searchParams().minScore(),
            retrievalResult.docs().size(),
            retrievalResult.effectiveHit(),
            buildDebugHits(retrievalResult.docs())
        );
    }

    /**
     * 把每次候选 query 的检索结果转换成可展示的调试结构。
     */
    private List<QueryDebugInfo.Candidate> buildCandidateDebug(List<RetrievalAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return List.of();
        }
        return attempts.stream()
            .map(attempt -> new QueryDebugInfo.Candidate(
                attempt.query(),
                attempt.docs().size(),
                attempt.effectiveHit(),
                attempt.rejectionReason(),
                buildDebugHits(attempt.docs())
            ))
            .toList();
    }

    /**
     * 将命中文档压缩成调试视图，避免直接暴露过长正文。
     */
    private List<QueryDebugInfo.Hit> buildDebugHits(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        return docs.stream()
            .map(doc -> new QueryDebugInfo.Hit(
                readKnowledgeBaseId(doc),
                buildPreview(doc.getText())
            ))
            .toList();
    }

    /**
     * 从文档 metadata 中读取知识库 ID，用于调试面板展示来源。
     */
    private String readKnowledgeBaseId(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        Object knowledgeBaseId = doc.getMetadata().get("kb_id");
        return knowledgeBaseId == null ? null : knowledgeBaseId.toString();
    }

    /**
     * 生成调试预览文本，压缩空白并限制长度，避免日志和接口返回过大。
     */
    private String buildPreview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= DEBUG_PREVIEW_CHARS) {
            return normalized;
        }
        return normalized.substring(0, DEBUG_PREVIEW_CHARS) + "...";
    }

    /**
     * 在 debug 日志打开时输出一次完整检索调试快照，方便本地排查问题。
     */
    private void logRetrievalDebug(List<Long> knowledgeBaseIds, QueryContext queryContext, RetrievalResult retrievalResult) {
        if (!log.isDebugEnabled()) {
            return;
        }
        QueryDebugInfo debugInfo = buildQueryDebugInfo(knowledgeBaseIds, queryContext, retrievalResult);
        log.debug("知识库检索调试信息: {}", debugInfo);
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(
        String originalQuestion,
        String rewrittenQuestion,
        List<String> candidateQueries,
        SearchParams searchParams,
        List<String> precisionTokens
    ) {
    }

    private record HitEvaluation(boolean effectiveHit, String rejectionReason) {
    }

    private record RetrievalAttempt(
        String query,
        List<Document> docs,
        boolean effectiveHit,
        String rejectionReason
    ) {
    }

    private record RetrievalResult(
        String retrievalQuery,
        List<Document> docs,
        boolean effectiveHit,
        List<RetrievalAttempt> attempts
    ) {
        private static RetrievalResult empty(List<RetrievalAttempt> attempts) {
            return new RetrievalResult(null, List.of(), false, List.copyOf(attempts));
        }
    }

    private record QueryExecutionResult(String answer, QueryDebugInfo debugInfo) {
    }
}
