package interview.guide.modules.agent.eval;

import interview.guide.modules.knowledgebase.model.QueryDebugInfo;
import interview.guide.modules.knowledgebase.model.QueryDebugResponse;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseCountService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStage7RagRetrievalEvalTest {

    private static final String SUITE_ID = "stage-7-rag-retrieval-set";
    private static final String JSON_REPORT_NAME = "stage-7-rag-retrieval-set-report.json";
    private static final String MARKDOWN_REPORT_NAME = "stage-7-rag-retrieval-set-report.md";
    private static final Long KNOWLEDGE_BASE_ID = 7001L;
    private static final String RAG_020_REJECTION_REASON = "missing_precision_tokens:OAuth2";
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("should run the fixed stage 7 RAG retrieval suite and persist reports")
    void shouldRunTheFixedStage7RagRetrievalSuiteAndPersistReports() throws Exception {
        Path reportDirectory = Path.of("build", "reports", "agent-eval");

        AgentStage7RagRetrievalReport report = runFixedSuite(reportDirectory);

        assertThat(report.summary().totalQueries()).isEqualTo(20);
        assertThat(report.summary().answerableQueries()).isEqualTo(17);
        assertThat(report.summary().noAnswerQueries()).isEqualTo(3);
        assertThat(report.summary().top1HitRate()).isEqualTo(100.0);
        assertThat(report.summary().top3HitRate()).isEqualTo(100.0);
        assertThat(report.summary().answerGroundedRate()).isEqualTo(100.0);
        assertThat(report.summary().noAnswerRejectionRate()).isEqualTo(100.0);
        assertThat(report.summary().hallucinationCount()).isZero();
        assertThat(report.caseResults()).allMatch(AgentStage7RagRetrievalCaseResult::passed);
        assertThat(report.caseResults())
            .filteredOn(result -> "RAG-020".equals(result.caseId()))
            .singleElement()
            .satisfies(result -> {
                assertThat(rawCandidateHitCount(result.candidates())).isGreaterThan(0);
                assertThat(rejectionReasons(result.candidates())).isEqualTo(RAG_020_REJECTION_REASON);
                assertThat(result.noAnswerRejected()).isTrue();
            });

        Path jsonReport = reportDirectory.resolve(JSON_REPORT_NAME);
        Path markdownReport = reportDirectory.resolve(MARKDOWN_REPORT_NAME);

        assertThat(Files.exists(jsonReport)).isTrue();
        assertThat(Files.exists(markdownReport)).isTrue();
        assertThat(Files.readString(markdownReport))
            .contains("top1HitRate")
            .contains("answerGroundedRate")
            .contains("noAnswerRejectionRate")
            .contains("RawCandidateHits")
            .contains("hallucinationCount");
    }

    private AgentStage7RagRetrievalReport runFixedSuite(Path reportDirectory) throws Exception {
        List<RagRetrievalScenario> scenarios = buildScenarios();
        List<AgentStage7RagRetrievalCaseResult> caseResults = new ArrayList<>();
        for (RagRetrievalScenario scenario : scenarios) {
            caseResults.add(executeScenario(scenario));
        }

        AgentStage7RagRetrievalReport report = new AgentStage7RagRetrievalReport(
            SUITE_ID,
            LocalDateTime.now().toString(),
            buildSummary(caseResults),
            caseResults
        );
        writeReport(reportDirectory, report);
        return report;
    }

    private AgentStage7RagRetrievalCaseResult executeScenario(RagRetrievalScenario scenario) {
        long startedAt = System.nanoTime();
        QueryDebugResponse response;
        try {
            response = createHarness(scenario).queryService().queryKnowledgeBaseWithDebug(
                new QueryRequest(List.of(KNOWLEDGE_BASE_ID), scenario.query())
            );
        } catch (Exception error) {
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return AgentStage7RagRetrievalCaseResult.error(
                scenario.caseId(),
                scenario.queryType(),
                scenario.query(),
                scenario.expectedSource(),
                scenario.expectedSection(),
                latencyMs,
                error
            );
        }

        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        QueryDebugInfo debug = response.debug();
        List<QueryDebugInfo.Hit> hits = debug == null ? List.of() : debug.hits();
        List<CandidateSummary> candidates = debug == null ? List.of() : candidateSummaries(debug.candidates());
        boolean effectiveHit = debug != null && debug.effectiveHit();
        boolean top1Hit = isExpectedHit(hits, 1, scenario);
        boolean top3Hit = isExpectedHit(hits, 3, scenario);
        boolean weakHitRejected = weakHitRejectionMatched(scenario, candidates);
        boolean noAnswerRejected = scenario.noAnswer()
            && !effectiveHit
            && response.answer() != null
            && NO_RESULT_RESPONSE.equals(response.answer())
            && weakHitRejected
            && response.answer().contains("未检索到相关信息");
        boolean groundedAnswer = !scenario.noAnswer()
            && effectiveHit
            && answerContainsAll(response.answer(), List.of(scenario.contextSentinel()))
            && hitPreviewsContainAll(hits, scenario.expectedEvidence())
            && hitPreviewsContainAll(hits, List.of(scenario.contextSentinel()))
            && answerContainsAll(response.answer(), scenario.expectedEvidence());
        boolean hallucinated = scenario.noAnswer() && !noAnswerRejected;
        boolean passed = scenario.noAnswer()
            ? noAnswerRejected && !hallucinated
            : top1Hit && top3Hit && groundedAnswer;

        return new AgentStage7RagRetrievalCaseResult(
            scenario.caseId(),
            scenario.queryType(),
            scenario.query(),
            scenario.expectedSource(),
            scenario.expectedSection(),
            scenario.expectedEvidence(),
            effectiveHit,
            top1Hit,
            top3Hit,
            groundedAnswer,
            noAnswerRejected,
            hallucinated,
            debug == null ? null : debug.retrievalQuery(),
            debug == null ? 0 : debug.hitCount(),
            hitSummaries(hits),
            candidates,
            preview(response.answer()),
            latencyMs,
            passed,
            passed ? "matched retrieval and answer expectations" : "expectation mismatch"
        );
    }

    private RetrievalEvalHarness createHarness(RagRetrievalScenario scenario) throws Exception {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec answerRequestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec answerResponseSpec = mock(ChatClient.CallResponseSpec.class);
        KnowledgeBaseVectorService vectorService = mock(KnowledgeBaseVectorService.class);
        KnowledgeBaseListService listService = mock(KnowledgeBaseListService.class);
        KnowledgeBaseCountService countService = mock(KnowledgeBaseCountService.class);
        AtomicReference<String> capturedUserPrompt = new AtomicReference<>("");

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(answerRequestSpec);
        when(answerRequestSpec.system(anyString())).thenReturn(answerRequestSpec);
        when(answerRequestSpec.user(anyString())).thenAnswer(invocation -> {
            capturedUserPrompt.set(invocation.getArgument(0, String.class));
            return answerRequestSpec;
        });
        when(answerRequestSpec.call()).thenReturn(answerResponseSpec);
        when(answerResponseSpec.content()).thenAnswer(invocation -> answerFromPrompt(scenario, capturedUserPrompt.get()));
        when(listService.getKnowledgeBaseNames(List.of(KNOWLEDGE_BASE_ID))).thenReturn(List.of("stage-7-rag-corpus"));
        when(vectorService.similaritySearch(anyString(), any(), any(Integer.class), any(Double.class)))
            .thenReturn(scenario.documents());

        KnowledgeBaseQueryService queryService = new KnowledgeBaseQueryService(
            chatClientBuilder,
            vectorService,
            listService,
            countService,
            new ByteArrayResource("system".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("context={context}\nquestion={question}".getBytes(StandardCharsets.UTF_8)),
            new ByteArrayResource("{question}".getBytes(StandardCharsets.UTF_8)),
            false,
            4,
            20,
            12,
            8,
            0.18,
            0.28
        );
        return new RetrievalEvalHarness(queryService);
    }

    private List<RagRetrievalScenario> buildScenarios() {
        return List.of(
            answerable("RAG-001", "concept", "volatile count++ atomicity", "java-concurrency.md", "volatile", List.of("volatile", "count++", "AtomicInteger")),
            answerable("RAG-002", "concept", "ThreadPoolExecutor queue full rejection policy", "java-concurrency.md", "thread pool", List.of("ThreadPoolExecutor", "queue", "rejectedExecutionHandler")),
            answerable("RAG-003", "concept", "Java heap space OOM troubleshooting", "jvm-gc.md", "OOM troubleshooting", List.of("Java heap space", "heap dump", "leak")),
            answerable("RAG-004", "concept", "Spring transaction self invocation proxy failure", "spring-tx-aop.md", "transaction proxy", List.of("self invocation", "proxy", "@Transactional")),
            answerable("RAG-005", "concept", "Redis cache penetration breakdown avalanche", "redis-cache.md", "cache failure patterns", List.of("penetration", "breakdown", "avalanche")),
            answerable("RAG-006", "concept", "MySQL composite index leftmost prefix range condition", "mysql-index.md", "composite index", List.of("leftmost prefix", "range condition", "composite index")),
            answerable("RAG-007", "concept", "rate limiting fixed window sliding window token bucket", "system-design.md", "rate limiting algorithms", List.of("fixed window", "sliding window", "token bucket")),
            answerable("RAG-008", "grounded-answer", "interview rubric evidence recording", "interview-rubric.md", "evidence record", List.of("evidence record", "observable behavior", "score")),
            answerable("RAG-009", "grounded-answer", "STAR template Result metrics", "star-template.md", "Result", List.of("Result", "metrics", "business impact")),
            answerable("RAG-010", "grounded-answer", "resume RAG project chunk metadata highlights", "resume-highlights.md", "project description", List.of("RAG", "chunk metadata", "sourceTitle")),
            answerable("RAG-011", "grounded-answer", "backend JD Java database cache capabilities", "backend-jd.md", "required skills", List.of("Java", "database", "cache")),
            answerable("RAG-012", "precision-term", "AOP", "spring-tx-aop.md", "AOP aspect order", List.of("AOP", "@Order", "proxy")),
            answerable("RAG-013", "precision-term", "MVCC", "mysql-index.md", "transaction isolation", List.of("MVCC", "consistent read", "InnoDB")),
            answerable("RAG-014", "precision-term", "CMS", "jvm-gc.md", "garbage collectors", List.of("CMS", "low pause", "fragmentation")),
            answerable("RAG-015", "precision-term", "CAS", "java-concurrency.md", "volatile", List.of("CAS", "AtomicInteger", "retry")),
            answerable("RAG-016", "precision-term", "Bloom filter", "redis-cache.md", "cache failure patterns", List.of("Bloom filter", "cache penetration", "false positive")),
            answerable("RAG-017", "grounded-answer", "Redis distributed lock SET NX PX Lua release", "redis-cache.md", "distributed lock", List.of("SET NX PX", "Lua", "value")),
            noAnswer("RAG-018", "no-answer", "Kubernetes HPA scaling metrics", "none", "none"),
            noAnswer("RAG-019", "no-answer", "Elasticsearch inverted index segment merge", "none", "none"),
            noAnswerWithWeakHits("RAG-020", "no-answer", "OAuth2 authorization code PKCE flow", "none", "none")
        );
    }

    private RagRetrievalScenario answerable(
        String caseId,
        String queryType,
        String query,
        String expectedSource,
        String expectedSection,
        List<String> expectedEvidence
    ) {
        Document primary = document(expectedSource, expectedSection, 1, String.join(" ", expectedEvidence) + " " + query);
        String contextSentinel = caseId + "-context-only";
        primary = document(expectedSource, expectedSection, 1, String.join(" ", expectedEvidence) + " " + contextSentinel + " " + query);
        Document distractor = document("distractor.md", "unrelated", 2, "unrelated interview notes");
        return new RagRetrievalScenario(
            caseId,
            queryType,
            query,
            expectedSource,
            expectedSection,
            expectedEvidence,
            contextSentinel,
            false,
            List.of(primary, distractor)
        );
    }

    private RagRetrievalScenario noAnswer(String caseId, String queryType, String query, String expectedSource, String expectedSection) {
        return new RagRetrievalScenario(
            caseId,
            queryType,
            query,
            expectedSource,
            expectedSection,
            List.of(),
            "",
            true,
            List.of()
        );
    }

    private RagRetrievalScenario noAnswerWithWeakHits(String caseId, String queryType, String query, String expectedSource, String expectedSection) {
        Document weakHit = document(
            "auth-notes.md",
            "authorization overview",
            1,
            "Authorization code login flow and redirect validation are covered here, but this corpus intentionally omits the PKCE verifier and challenge mechanism."
        );
        return new RagRetrievalScenario(
            caseId,
            queryType,
            query,
            expectedSource,
            expectedSection,
            List.of(),
            "",
            true,
            List.of(weakHit)
        );
    }

    private String answerFromPrompt(RagRetrievalScenario scenario, String userPrompt) {
        if (scenario.noAnswer()) {
            return "unexpected generated answer for no-answer case";
        }
        if (!answerContainsAll(userPrompt, scenario.expectedEvidence()) || !userPrompt.contains(scenario.contextSentinel())) {
            return "insufficient retrieved context for " + scenario.caseId();
        }
        return "Grounded answer from retrieved context: " + String.join("; ", scenario.expectedEvidence())
            + "; " + scenario.contextSentinel();
    }

    private Document document(String sourceTitle, String sectionTitle, int chunkIndex, String text) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kb_id", KNOWLEDGE_BASE_ID.toString());
        metadata.put("source_title", sourceTitle);
        metadata.put("section_title", sectionTitle);
        metadata.put("chunk_index", chunkIndex);
        metadata.put("preview", preview(text));
        return new Document(text, metadata);
    }

    private AgentStage7RagRetrievalSummary buildSummary(List<AgentStage7RagRetrievalCaseResult> caseResults) {
        int totalQueries = caseResults.size();
        int passedQueries = (int) caseResults.stream().filter(AgentStage7RagRetrievalCaseResult::passed).count();
        int answerableQueries = (int) caseResults.stream().filter(result -> !"no-answer".equals(result.queryType())).count();
        int noAnswerQueries = totalQueries - answerableQueries;
        int top1Hits = (int) caseResults.stream().filter(result -> !"no-answer".equals(result.queryType())).filter(AgentStage7RagRetrievalCaseResult::top1Hit).count();
        int top3Hits = (int) caseResults.stream().filter(result -> !"no-answer".equals(result.queryType())).filter(AgentStage7RagRetrievalCaseResult::top3Hit).count();
        int groundedAnswers = (int) caseResults.stream().filter(result -> !"no-answer".equals(result.queryType())).filter(AgentStage7RagRetrievalCaseResult::answerGrounded).count();
        int noAnswerRejections = (int) caseResults.stream().filter(result -> "no-answer".equals(result.queryType())).filter(AgentStage7RagRetrievalCaseResult::noAnswerRejected).count();
        int hallucinationCount = (int) caseResults.stream().filter(AgentStage7RagRetrievalCaseResult::hallucinated).count();
        long averageLatencyMs = Math.round(caseResults.stream().mapToLong(AgentStage7RagRetrievalCaseResult::latencyMs).average().orElse(0));
        long maxLatencyMs = caseResults.stream().mapToLong(AgentStage7RagRetrievalCaseResult::latencyMs).max().orElse(0);

        return new AgentStage7RagRetrievalSummary(
            totalQueries,
            passedQueries,
            answerableQueries,
            noAnswerQueries,
            toPercent(top1Hits, answerableQueries),
            toPercent(top3Hits, answerableQueries),
            toPercent(groundedAnswers, answerableQueries),
            toPercent(noAnswerRejections, noAnswerQueries),
            hallucinationCount,
            averageLatencyMs,
            maxLatencyMs
        );
    }

    private void writeReport(Path reportDirectory, AgentStage7RagRetrievalReport report) throws Exception {
        Files.createDirectories(reportDirectory);
        Files.writeString(
            reportDirectory.resolve(JSON_REPORT_NAME),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        );
        Files.writeString(reportDirectory.resolve(MARKDOWN_REPORT_NAME), toMarkdown(report));
    }

    private String toMarkdown(AgentStage7RagRetrievalReport report) {
        AgentStage7RagRetrievalSummary summary = report.summary();
        StringBuilder builder = new StringBuilder();
        builder.append("# Stage 7 RAG Retrieval Set Report\n\n");
        builder.append("- suite: ").append(report.suiteId()).append('\n');
        builder.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        builder.append("- totalQueries: ").append(summary.totalQueries()).append('\n');
        builder.append("- passedQueries: ").append(summary.passedQueries()).append('\n');
        builder.append("- answerableQueries: ").append(summary.answerableQueries()).append('\n');
        builder.append("- noAnswerQueries: ").append(summary.noAnswerQueries()).append('\n');
        builder.append("- top1HitRate: ").append(summary.top1HitRate()).append("%\n");
        builder.append("- top3HitRate: ").append(summary.top3HitRate()).append("%\n");
        builder.append("- answerGroundedRate: ").append(summary.answerGroundedRate()).append("%\n");
        builder.append("- noAnswerRejectionRate: ").append(summary.noAnswerRejectionRate()).append("%\n");
        builder.append("- hallucinationCount: ").append(summary.hallucinationCount()).append('\n');
        builder.append("- averageLatencyMs: ").append(summary.averageLatencyMs()).append('\n');
        builder.append("- maxLatencyMs: ").append(summary.maxLatencyMs()).append("\n\n");
        builder.append("| Case | Type | Top1 | Top3 | Grounded | NoAnswerRejected | Hallucinated | HitCount | RawCandidateHits | RejectionReason | Passed | Note |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (AgentStage7RagRetrievalCaseResult result : report.caseResults()) {
            builder.append("| ")
                .append(result.caseId())
                .append(" | ")
                .append(result.queryType())
                .append(" | ")
                .append(result.top1Hit())
                .append(" | ")
                .append(result.top3Hit())
                .append(" | ")
                .append(result.answerGrounded())
                .append(" | ")
                .append(result.noAnswerRejected())
                .append(" | ")
                .append(result.hallucinated())
                .append(" | ")
                .append(result.hitCount())
                .append(" | ")
                .append(rawCandidateHitCount(result.candidates()))
                .append(" | ")
                .append(rejectionReasons(result.candidates()))
                .append(" | ")
                .append(result.passed())
                .append(" | ")
                .append(result.note())
                .append(" |\n");
        }
        return builder.toString();
    }

    private boolean isExpectedHit(List<QueryDebugInfo.Hit> hits, int limit, RagRetrievalScenario scenario) {
        if (scenario.noAnswer() || hits == null || hits.isEmpty()) {
            return false;
        }
        return hits.stream()
            .limit(limit)
            .anyMatch(hit -> Objects.equals(hit.sourceTitle(), scenario.expectedSource())
                && Objects.equals(hit.sectionTitle(), scenario.expectedSection()));
    }

    private boolean weakHitRejectionMatched(RagRetrievalScenario scenario, List<CandidateSummary> candidates) {
        if (!scenario.noAnswer() || scenario.documents().isEmpty()) {
            return true;
        }
        return candidates.stream()
            .anyMatch(candidate -> candidate.rawHitCount() > 0
                && !candidate.effectiveHit()
                && Objects.equals(candidate.rejectionReason(), RAG_020_REJECTION_REASON));
    }

    private boolean hitPreviewsContainAll(List<QueryDebugInfo.Hit> hits, List<String> expectedEvidence) {
        if (hits == null || hits.isEmpty() || expectedEvidence == null) {
            return false;
        }
        String combinedPreview = hits.stream()
            .map(QueryDebugInfo.Hit::preview)
            .filter(Objects::nonNull)
            .reduce("", (left, right) -> left + " " + right);
        return answerContainsAll(combinedPreview, expectedEvidence);
    }

    private boolean answerContainsAll(String answer, List<String> expectedEvidence) {
        if (answer == null || expectedEvidence == null) {
            return false;
        }
        String lowerAnswer = answer.toLowerCase();
        return expectedEvidence.stream().allMatch(token -> lowerAnswer.contains(token.toLowerCase()));
    }

    private List<HitSummary> hitSummaries(List<QueryDebugInfo.Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
            .map(hit -> new HitSummary(hit.sourceTitle(), hit.sectionTitle(), hit.chunkIndex(), hit.preview()))
            .toList();
    }

    private List<CandidateSummary> candidateSummaries(List<QueryDebugInfo.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
            .map(candidate -> new CandidateSummary(
                candidate.query(),
                candidate.rawHitCount(),
                candidate.effectiveHit(),
                candidate.rejectionReason(),
                hitSummaries(candidate.hits())
            ))
            .toList();
    }

    private int rawCandidateHitCount(List<CandidateSummary> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        return candidates.stream().mapToInt(CandidateSummary::rawHitCount).sum();
    }

    private String rejectionReasons(List<CandidateSummary> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "n/a";
        }
        String reasons = candidates.stream()
            .map(CandidateSummary::rejectionReason)
            .filter(Objects::nonNull)
            .filter(reason -> !reason.isBlank())
            .distinct()
            .reduce("", (left, right) -> left.isBlank() ? right : left + "; " + right);
        return reasons.isBlank() ? "n/a" : preview(reasons);
    }

    private static double toPercent(int numerator, int denominator) {
        if (denominator == 0) {
            return 0;
        }
        return Math.round((numerator * 10000.0) / denominator) / 100.0;
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").replace("|", "/").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }

    private record RetrievalEvalHarness(KnowledgeBaseQueryService queryService) {
    }

    private record RagRetrievalScenario(
        String caseId,
        String queryType,
        String query,
        String expectedSource,
        String expectedSection,
        List<String> expectedEvidence,
        String contextSentinel,
        boolean noAnswer,
        List<Document> documents
    ) {
    }

    private record HitSummary(
        String sourceTitle,
        String sectionTitle,
        Integer chunkIndex,
        String preview
    ) {
    }

    private record CandidateSummary(
        String query,
        int rawHitCount,
        boolean effectiveHit,
        String rejectionReason,
        List<HitSummary> hits
    ) {
    }

    private record AgentStage7RagRetrievalSummary(
        int totalQueries,
        int passedQueries,
        int answerableQueries,
        int noAnswerQueries,
        double top1HitRate,
        double top3HitRate,
        double answerGroundedRate,
        double noAnswerRejectionRate,
        int hallucinationCount,
        long averageLatencyMs,
        long maxLatencyMs
    ) {
    }

    private record AgentStage7RagRetrievalCaseResult(
        String caseId,
        String queryType,
        String query,
        String expectedSource,
        String expectedSection,
        List<String> expectedEvidence,
        boolean effectiveHit,
        boolean top1Hit,
        boolean top3Hit,
        boolean answerGrounded,
        boolean noAnswerRejected,
        boolean hallucinated,
        String retrievalQuery,
        int hitCount,
        List<HitSummary> hits,
        List<CandidateSummary> candidates,
        String answerPreview,
        long latencyMs,
        boolean passed,
        String note
    ) {
        static AgentStage7RagRetrievalCaseResult error(
            String caseId,
            String queryType,
            String query,
            String expectedSource,
            String expectedSection,
            long latencyMs,
            Exception error
        ) {
            return new AgentStage7RagRetrievalCaseResult(
                caseId,
                queryType,
                query,
                expectedSource,
                expectedSection,
                List.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                0,
                List.of(),
                List.of(),
                "",
                latencyMs,
                false,
                preview(error.getClass().getSimpleName() + ": " + error.getMessage())
            );
        }
    }

    private record AgentStage7RagRetrievalReport(
        String suiteId,
        String generatedAt,
        AgentStage7RagRetrievalSummary summary,
        List<AgentStage7RagRetrievalCaseResult> caseResults
    ) {
    }
}
