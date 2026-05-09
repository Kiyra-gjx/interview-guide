package interview.guide.modules.agent.eval;

import interview.guide.App;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.model.QueryDebugInfo;
import interview.guide.modules.knowledgebase.model.QueryDebugResponse;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.infrastructure.redis.RedisService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStage8RagE2eEvalTest {

    private static final String SUITE_ID = "stage-8-rag-e2e";
    private static final String JSON_REPORT_NAME = "stage-8-rag-e2e-report.json";
    private static final String MARKDOWN_REPORT_NAME = "stage-8-rag-e2e-report.md";
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final Path REPORT_DIRECTORY = Path.of("build", "reports", "agent-eval");
    private static final Path DATASET_PATH = Path.of("..", "docs", "evidence", "agent-quantification", "stage-8-rag-e2e", "eval-dataset", "relevance-judgments.json");
    private static final Path REFERENCE_ANSWER_PATH = Path.of("..", "docs", "evidence", "agent-quantification", "stage-8-rag-e2e", "eval-dataset", "reference-answers.json");
    private static final Path CORPUS_PATH = Path.of("..", "docs", "evidence", "agent-quantification", "stage-7-rag-corpus", "sample-docs");
    private static final List<Integer> TOP_KS = List.of(3, 5, 10);

    private KnowledgeBaseRepository knowledgeBaseRepository;
    private KnowledgeBaseVectorService vectorService;
    private KnowledgeBaseQueryService queryService;
    private ChatClient judgeClient;
    private Properties previousSystemProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("should run the opt-in real Stage 8 RAG e2e suite and persist reports")
    void shouldRunTheOptInRealStage8RagE2eSuiteAndPersistReports() throws Exception {
        Assumptions.assumeTrue(stage8Enabled(), "Stage 8 real RAG e2e eval is disabled. Set APP_STAGE8_RAG_E2E_ENABLED=true to run it.");
        Assumptions.assumeTrue(apiKeyConfigured(), "AI_BAILIAN_API_KEY is required for real embedding and LLM calls.");
        Assumptions.assumeTrue(pgvectorAvailable(), "PostgreSQL + pgvector is required for Stage 8 real RAG e2e eval.");

        try (ConfigurableApplicationContext context = startStage8Context()) {
            this.knowledgeBaseRepository = context.getBean(KnowledgeBaseRepository.class);
            this.vectorService = context.getBean(KnowledgeBaseVectorService.class);
            this.queryService = context.getBean(KnowledgeBaseQueryService.class);
            this.judgeClient = context.getBean(ChatClient.Builder.class).build();

            Stage8Dataset dataset = loadDataset();
            Map<String, ReferenceAnswer> referenceAnswers = loadReferenceAnswers();
            Map<String, Long> knowledgeBaseIds = seedCorpus(dataset);
            List<Stage8Query> queries = limitQueries(dataset.queries());

            List<Stage8CaseResult> caseResults = new ArrayList<>();
            for (Stage8Query query : queries) {
                caseResults.add(executeCase(query, referenceAnswers.get(query.id()), knowledgeBaseIds));
            }

            Stage8RagE2eReport report = new Stage8RagE2eReport(
                SUITE_ID,
                LocalDateTime.now().toString(),
                buildConfig(dataset),
                buildSummary(caseResults),
                caseResults
            );
            writeReport(report);

            assertThat(report.summary().totalQueries()).isEqualTo(queries.size());
            assertThat(report.summary().answerableQueries()).isGreaterThan(0);
            assertThat(report.summary().skipped()).isFalse();
            assertThat(Files.exists(REPORT_DIRECTORY.resolve(JSON_REPORT_NAME))).isTrue();
            assertThat(Files.exists(REPORT_DIRECTORY.resolve(MARKDOWN_REPORT_NAME))).isTrue();
        } finally {
            restoreSystemProperties();
        }
    }

    private ConfigurableApplicationContext startStage8Context() {
        Stage8DatabaseConfig database = resolveStage8DatabaseConfig()
            .orElseThrow(() -> new IllegalStateException("PostgreSQL + pgvector is required for Stage 8 real RAG e2e eval."));
        ensureStage8SchemaCompatibility(database);
        overrideSystemProperty("spring.datasource.url", database.url());
        overrideSystemProperty("spring.datasource.username", database.user());
        overrideSystemProperty("spring.datasource.password", database.password());
        overrideSystemProperty("spring.ai.openai.api-key", System.getenv("AI_BAILIAN_API_KEY"));
        return new SpringApplicationBuilder(App.class, Stage8TestConfig.class)
            .web(WebApplicationType.NONE)
            .properties(Map.of(
                "app.stage8.rag-e2e.enabled", "true",
                "app.storage.access-key", "stage8-test-access-key",
                "app.storage.secret-key", "stage8-test-secret-key",
                "app.knowledge-base.delete-cleanup-interval-ms", "86400000",
                "spring.autoconfigure.exclude", String.join(",",
                    "org.redisson.spring.starter.RedissonAutoConfigurationV2",
                    "org.redisson.spring.starter.RedissonAutoConfigurationV4",
                    "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
                    "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
                    "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
                    "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
                )
            ))
            .run();
    }

    private void overrideSystemProperty(String key, String value) {
        if (previousSystemProperties == null) {
            previousSystemProperties = new Properties();
        }
        if (!previousSystemProperties.containsKey(key)) {
            String previous = System.getProperty(key);
            if (previous != null) {
                previousSystemProperties.setProperty(key, previous);
            } else {
                previousSystemProperties.put(key, NULL_PROPERTY_VALUE.INSTANCE);
            }
        }
        System.setProperty(key, value);
    }

    private void restoreSystemProperties() {
        if (previousSystemProperties == null) {
            return;
        }
        for (String key : previousSystemProperties.stringPropertyNames()) {
            Object value = previousSystemProperties.get(key);
            if (value == NULL_PROPERTY_VALUE.INSTANCE) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value.toString());
            }
        }
        previousSystemProperties = null;
    }

    private boolean stage8Enabled() {
        return Boolean.parseBoolean(System.getProperty("app.stage8.rag-e2e.enabled", System.getenv().getOrDefault("APP_STAGE8_RAG_E2E_ENABLED", "false")));
    }

    private boolean apiKeyConfigured() {
        String key = System.getenv("AI_BAILIAN_API_KEY");
        return key != null && !key.isBlank() && !"stage8-disabled".equals(key);
    }

    private boolean pgvectorAvailable() {
        return resolveStage8DatabaseConfig().isPresent();
    }

    private Optional<Stage8DatabaseConfig> resolveStage8DatabaseConfig() {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        String database = System.getenv().getOrDefault("POSTGRES_DB", "interview_agent");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "123456");
        List<Stage8DatabaseConfig> candidates = new ArrayList<>();
        candidates.add(new Stage8DatabaseConfig(host, port, database, user, password));
        candidates.add(new Stage8DatabaseConfig(host, port, "interview_guide", "postgres", "123456"));
        candidates.add(new Stage8DatabaseConfig(host, port, "interview_guide", "postgres", "password"));
        candidates.add(new Stage8DatabaseConfig(host, port, "interview_agent", "postgres", "password"));

        return candidates.stream()
            .distinct()
            .filter(this::hasPgvector)
            .findFirst();
    }

    private boolean hasPgvector(Stage8DatabaseConfig config) {
        try {
            try (var connection = DriverManager.getConnection(config.url(), config.user(), config.password());
                 var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT extname FROM pg_extension WHERE extname = 'vector'")) {
                return resultSet.next() && "vector".equals(resultSet.getString(1));
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureStage8SchemaCompatibility(Stage8DatabaseConfig config) {
        try (var connection = DriverManager.getConnection(config.url(), config.user(), config.password());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                ALTER TABLE knowledge_bases
                ADD COLUMN IF NOT EXISTS lifecycle_status varchar(20)
                """);
            statement.executeUpdate("""
                UPDATE knowledge_bases
                SET lifecycle_status = 'ACTIVE'
                WHERE lifecycle_status IS NULL
                """);
            statement.executeUpdate("""
                ALTER TABLE knowledge_bases
                ALTER COLUMN lifecycle_status SET DEFAULT 'ACTIVE'
                """);
            statement.executeUpdate("""
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1
                        FROM pg_constraint
                        WHERE conname = 'knowledge_bases_lifecycle_status_check'
                    ) THEN
                        ALTER TABLE knowledge_bases
                        ADD CONSTRAINT knowledge_bases_lifecycle_status_check
                        CHECK (lifecycle_status IN ('ACTIVE', 'DELETING', 'DELETE_FAILED'));
                    END IF;
                END $$;
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_kb_lifecycle_status
                ON knowledge_bases (lifecycle_status)
                """);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare Stage 8 database schema compatibility.", e);
        }
    }

    private Stage8Dataset loadDataset() throws IOException {
        return objectMapper.readValue(Files.readString(DATASET_PATH), Stage8Dataset.class);
    }

    private List<Stage8Query> limitQueries(List<Stage8Query> queries) {
        String configuredLimit = System.getProperty(
            "app.stage8.rag-e2e.case-limit",
            System.getenv().getOrDefault("APP_STAGE8_RAG_E2E_CASE_LIMIT", "")
        );
        if (configuredLimit == null || configuredLimit.isBlank()) {
            return queries;
        }
        int limit = Integer.parseInt(configuredLimit);
        if (limit <= 0 || limit >= queries.size()) {
            return queries;
        }
        return queries.subList(0, limit);
    }

    private Map<String, ReferenceAnswer> loadReferenceAnswers() throws IOException {
        ReferenceAnswerSet answerSet = objectMapper.readValue(Files.readString(REFERENCE_ANSWER_PATH), ReferenceAnswerSet.class);
        Map<String, ReferenceAnswer> answers = new LinkedHashMap<>();
        for (ReferenceAnswer answer : answerSet.answers()) {
            answers.put(answer.queryId(), answer);
        }
        return answers;
    }

    private Map<String, Long> seedCorpus(Stage8Dataset dataset) throws Exception {
        Map<String, Long> knowledgeBaseIds = new LinkedHashMap<>();
        List<Path> corpusFiles;
        try (var stream = Files.list(CORPUS_PATH)) {
            corpusFiles = stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }

        for (Path docPath : corpusFiles) {
            String docId = docPath.getFileName().toString();
            String content = Files.readString(docPath, StandardCharsets.UTF_8);
            KnowledgeBaseEntity knowledgeBase = saveKnowledgeBase(docId, content);
            vectorService.vectorizeAndStore(knowledgeBase.getId(), content);
            knowledgeBase.setVectorStatus(VectorStatus.COMPLETED);
            knowledgeBaseRepository.save(knowledgeBase);
            knowledgeBaseIds.put(docId, knowledgeBase.getId());
        }
        assertThat(knowledgeBaseIds).hasSize(dataset.corpus().documentCount());
        return knowledgeBaseIds;
    }

    private KnowledgeBaseEntity saveKnowledgeBase(String docId, String content) throws Exception {
        String hash = sha256(SUITE_ID + ":" + docId + ":" + content);
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findByFileHash(hash).orElseGet(KnowledgeBaseEntity::new);
        entity.setFileHash(hash);
        entity.setName(docId);
        entity.setOriginalFilename(docId);
        entity.setCategory("stage-8-rag-e2e");
        entity.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        entity.setContentType("text/markdown");
        entity.setStorageKey("stage-8-rag-e2e/" + docId);
        entity.setStorageUrl("stage-8-rag-e2e/" + docId);
        entity.setAccessCount(entity.getAccessCount() == null ? 1 : entity.getAccessCount());
        entity.setQuestionCount(entity.getQuestionCount() == null ? 0 : entity.getQuestionCount());
        entity.setVectorStatus(VectorStatus.PROCESSING);
        entity.setVectorError(null);
        entity.setLifecycleStatus(KnowledgeBaseLifecycleStatus.ACTIVE);
        return knowledgeBaseRepository.save(entity);
    }

    private Stage8CaseResult executeCase(
        Stage8Query query,
        ReferenceAnswer referenceAnswer,
        Map<String, Long> knowledgeBaseIds
    ) {
        List<Long> queryKnowledgeBaseIds = resolveKnowledgeBaseIds(knowledgeBaseIds);
        long startedAt = System.nanoTime();
        QueryDebugResponse response;
        try {
            response = queryService.queryKnowledgeBaseWithDebug(new QueryRequest(queryKnowledgeBaseIds, query.query()));
        } catch (Exception e) {
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return Stage8CaseResult.error(query, latencyMs, e);
        }

        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        QueryDebugInfo debug = response.debug();
        List<QueryDebugInfo.Hit> hits = debug == null ? List.of() : debug.hits();
        RetrievalMetrics retrieval = calculateRetrievalMetrics(query, hits);
        GenerationScores generationScores = calculateGenerationScores(query, referenceAnswer, response.answer(), hits);
        boolean noAnswerMatched = !query.answerable() && NO_RESULT_RESPONSE.equals(response.answer());
        boolean passed = query.answerable()
            ? retrieval.hitRateAt3() == 1.0 && generationScores.correctness() >= 3.0 && generationScores.faithfulness() >= 3.0
            : noAnswerMatched;

        return new Stage8CaseResult(
            query.id(),
            query.queryType(),
            query.query(),
            query.answerable(),
            query.expectedSources(),
            debug == null ? null : debug.retrievalQuery(),
            debug == null ? 0 : debug.topK(),
            debug == null ? 0 : debug.minScore(),
            debug != null && debug.effectiveHit(),
            debug == null ? 0 : debug.hitCount(),
            hitSummaries(hits),
            retrieval,
            generationScores,
            noAnswerMatched,
            latencyMs,
            preview(response.answer()),
            passed,
            passed ? "matched Stage 8 expectations" : "expectation mismatch"
        );
    }

    private List<Long> resolveKnowledgeBaseIds(Map<String, Long> knowledgeBaseIds) {
        return knowledgeBaseIds.values().stream().toList();
    }

    private RetrievalMetrics calculateRetrievalMetrics(Stage8Query query, List<QueryDebugInfo.Hit> hits) {
        Map<String, Integer> relevanceBySource = new LinkedHashMap<>();
        for (RelevanceJudgment judgment : query.relevanceJudgments()) {
            relevanceBySource.merge(judgment.docId(), judgment.relevance(), Math::max);
        }
        long relevantDocCount = relevanceBySource.values().stream().filter(relevance -> relevance >= 2).count();
        if (relevantDocCount == 0) {
            return RetrievalMetrics.empty();
        }

        List<Integer> rankedRelevance = hits.stream()
            .map(QueryDebugInfo.Hit::sourceTitle)
            .filter(Objects::nonNull)
            .distinct()
            .map(sourceTitle -> relevanceBySource.getOrDefault(sourceTitle, 0))
            .toList();

        return new RetrievalMetrics(
            recallAt(rankedRelevance, relevantDocCount, 3),
            recallAt(rankedRelevance, relevantDocCount, 5),
            recallAt(rankedRelevance, relevantDocCount, 10),
            hitRateAt(rankedRelevance, 3),
            hitRateAt(rankedRelevance, 5),
            hitRateAt(rankedRelevance, 10),
            reciprocalRank(rankedRelevance),
            ndcgAt(rankedRelevance, relevanceBySource.values().stream().sorted(Comparator.reverseOrder()).toList(), 3),
            ndcgAt(rankedRelevance, relevanceBySource.values().stream().sorted(Comparator.reverseOrder()).toList(), 5),
            ndcgAt(rankedRelevance, relevanceBySource.values().stream().sorted(Comparator.reverseOrder()).toList(), 10)
        );
    }

    private double recallAt(List<Integer> rankedRelevance, long relevantDocCount, int k) {
        if (relevantDocCount == 0) {
            return 0;
        }
        long found = rankedRelevance.stream().limit(k).filter(relevance -> relevance >= 2).count();
        return round(found / (double) relevantDocCount);
    }

    private double hitRateAt(List<Integer> rankedRelevance, int k) {
        return rankedRelevance.stream().limit(k).anyMatch(relevance -> relevance >= 2) ? 1.0 : 0.0;
    }

    private double reciprocalRank(List<Integer> rankedRelevance) {
        for (int i = 0; i < rankedRelevance.size(); i++) {
            if (rankedRelevance.get(i) >= 2) {
                return round(1.0 / (i + 1));
            }
        }
        return 0;
    }

    private double ndcgAt(List<Integer> rankedRelevance, List<Integer> idealRelevance, int k) {
        double dcg = dcg(rankedRelevance, k);
        double idcg = dcg(idealRelevance, k);
        if (idcg == 0) {
            return 0;
        }
        return round(dcg / idcg);
    }

    private double dcg(List<Integer> relevance, int k) {
        double total = 0;
        for (int i = 0; i < Math.min(k, relevance.size()); i++) {
            total += relevance.get(i) / (Math.log(i + 2) / Math.log(2));
        }
        return total;
    }

    private GenerationScores calculateGenerationScores(
        Stage8Query query,
        ReferenceAnswer referenceAnswer,
        String answer,
        List<QueryDebugInfo.Hit> hits
    ) {
        if (!query.answerable()) {
            double noAnswerScore = NO_RESULT_RESPONSE.equals(answer) ? 5.0 : 0.0;
            return new GenerationScores(noAnswerScore, noAnswerScore, noAnswerScore, noAnswerScore, readabilityScore(answer), "no-answer-rule");
        }
        if (referenceAnswer == null) {
            return GenerationScores.empty();
        }
        String combinedEvidence = hits.stream()
            .map(QueryDebugInfo.Hit::preview)
            .filter(Objects::nonNull)
            .reduce("", (left, right) -> left + " " + right);

        if (judgeClient != null) {
            try {
                return judgeWithLlm(query, referenceAnswer, answer, combinedEvidence);
            } catch (Exception ignored) {
                return heuristicGenerationScores(referenceAnswer, answer, hits, combinedEvidence);
            }
        }
        return heuristicGenerationScores(referenceAnswer, answer, hits, combinedEvidence);
    }

    private GenerationScores judgeWithLlm(
        Stage8Query query,
        ReferenceAnswer referenceAnswer,
        String answer,
        String retrievedContext
    ) throws IOException {
        String judgePrompt = """
            你是一个 RAG 回答质量评估专家。请只根据给定信息评分，不要引入外部知识。

            ## 用户问题
            %s

            ## 检索到的证据
            %s

            ## 参考答案
            %s

            ## 参考要点
            %s

            ## RAG 回答
            %s

            请返回严格 JSON，不要 Markdown，不要解释。字段为：
            {
              "correctness": 0-5,
              "attribution": 0-5,
              "completeness": 0-5,
              "faithfulness": 0-5,
              "readability": 0-5
            }
            """.formatted(
            query.query(),
            retrievedContext,
            referenceAnswer.referenceAnswer(),
            String.join(", ", referenceAnswer.keyPoints()),
            answer
        );
        String rawJudgeResult = judgeClient.prompt()
            .user(judgePrompt)
            .call()
            .content();
        Map<String, Object> parsed = objectMapper.readValue(extractJsonObject(rawJudgeResult), new TypeReference<>() {
        });
        return new GenerationScores(
            scoreField(parsed, "correctness"),
            scoreField(parsed, "attribution"),
            scoreField(parsed, "completeness"),
            scoreField(parsed, "faithfulness"),
            scoreField(parsed, "readability"),
            "llm-as-judge"
        );
    }

    private GenerationScores heuristicGenerationScores(
        ReferenceAnswer referenceAnswer,
        String answer,
        List<QueryDebugInfo.Hit> hits,
        String combinedEvidence
    ) {
        double keyPointCoverage = keyPointCoverage(answer, referenceAnswer.keyPoints());
        double evidenceCoverage = keyPointCoverage(combinedEvidence, referenceAnswer.keyPoints());
        double attribution = hits.isEmpty() ? 0.0 : Math.max(2.0, evidenceCoverage);
        double faithfulness = answer != null && !answer.isBlank() && !hits.isEmpty() ? Math.max(3.0, Math.min(5.0, evidenceCoverage + 1.0)) : 0.0;
        return new GenerationScores(
            keyPointCoverage,
            attribution,
            keyPointCoverage,
            faithfulness,
            readabilityScore(answer),
            "rubric-fallback"
        );
    }

    private String extractJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private double scoreField(Map<String, Object> parsed, String field) {
        Object value = parsed.get(field);
        if (value instanceof Number number) {
            return clampScore(number.doubleValue());
        }
        if (value != null) {
            try {
                return clampScore(Double.parseDouble(value.toString()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private double clampScore(double value) {
        return round(Math.max(0, Math.min(5, value)));
    }

    private double keyPointCoverage(String text, List<String> keyPoints) {
        if (text == null || text.isBlank() || keyPoints == null || keyPoints.isEmpty()) {
            return 0;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        long matched = keyPoints.stream()
            .filter(point -> point != null && !point.isBlank())
            .filter(point -> normalized.contains(point.toLowerCase(Locale.ROOT)))
            .count();
        return round((matched * 5.0) / keyPoints.size());
    }

    private double readabilityScore(String answer) {
        if (answer == null || answer.isBlank()) {
            return 0;
        }
        if (answer.length() < 20) {
            return 2.0;
        }
        return answer.contains("\n") || answer.contains("。") || answer.contains("；") ? 5.0 : 4.0;
    }

    private Stage8Summary buildSummary(List<Stage8CaseResult> caseResults) {
        int total = caseResults.size();
        int answerable = (int) caseResults.stream().filter(Stage8CaseResult::answerable).count();
        int noAnswer = total - answerable;
        int passed = (int) caseResults.stream().filter(Stage8CaseResult::passed).count();
        List<RetrievalMetrics> retrievalMetrics = caseResults.stream()
            .filter(Stage8CaseResult::answerable)
            .map(Stage8CaseResult::retrievalMetrics)
            .toList();
        List<GenerationScores> generationScores = caseResults.stream()
            .filter(Stage8CaseResult::answerable)
            .map(Stage8CaseResult::generationScores)
            .toList();
        return new Stage8Summary(
            total,
            passed,
            answerable,
            noAnswer,
            false,
            average(retrievalMetrics, RetrievalMetrics::recallAt3),
            average(retrievalMetrics, RetrievalMetrics::recallAt5),
            average(retrievalMetrics, RetrievalMetrics::recallAt10),
            average(retrievalMetrics, RetrievalMetrics::hitRateAt3),
            average(retrievalMetrics, RetrievalMetrics::hitRateAt5),
            average(retrievalMetrics, RetrievalMetrics::hitRateAt10),
            average(retrievalMetrics, RetrievalMetrics::mrr),
            average(retrievalMetrics, RetrievalMetrics::ndcgAt3),
            average(retrievalMetrics, RetrievalMetrics::ndcgAt5),
            average(retrievalMetrics, RetrievalMetrics::ndcgAt10),
            average(generationScores, GenerationScores::correctness),
            average(generationScores, GenerationScores::attribution),
            average(generationScores, GenerationScores::completeness),
            average(generationScores, GenerationScores::faithfulness),
            average(generationScores, GenerationScores::readability),
            percentile(caseResults.stream().map(Stage8CaseResult::latencyMs).toList(), 0.50),
            percentile(caseResults.stream().map(Stage8CaseResult::latencyMs).toList(), 0.95),
            percentile(caseResults.stream().map(Stage8CaseResult::latencyMs).toList(), 0.99)
        );
    }

    private <T> double average(List<T> values, java.util.function.ToDoubleFunction<T> extractor) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return round(values.stream().mapToDouble(extractor).average().orElse(0));
    }

    private long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private Stage8Config buildConfig(Stage8Dataset dataset) {
        return new Stage8Config(
            dataset.version(),
            dataset.corpus().source(),
            "text-embedding-v3",
            System.getenv().getOrDefault("AI_MODEL", "qwen-plus"),
            TOP_KS,
            0.28,
            "real KnowledgeBaseVectorService + KnowledgeBaseQueryService",
            "llm-as-judge with rubric fallback"
        );
    }

    private List<HitSummary> hitSummaries(List<QueryDebugInfo.Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
            .map(hit -> new HitSummary(hit.sourceTitle(), hit.sectionTitle(), hit.chunkIndex(), preview(hit.preview())))
            .toList();
    }

    private void writeReport(Stage8RagE2eReport report) throws IOException {
        Files.createDirectories(REPORT_DIRECTORY);
        Files.writeString(
            REPORT_DIRECTORY.resolve(JSON_REPORT_NAME),
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        );
        Files.writeString(REPORT_DIRECTORY.resolve(MARKDOWN_REPORT_NAME), toMarkdown(report));
    }

    private String toMarkdown(Stage8RagE2eReport report) {
        Stage8Summary summary = report.summary();
        StringBuilder builder = new StringBuilder();
        builder.append("# Stage 8 RAG E2E Report\n\n");
        builder.append("- suite: ").append(report.suiteId()).append('\n');
        builder.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        builder.append("- corpus: ").append(report.config().corpus()).append('\n');
        builder.append("- embeddingModel: ").append(report.config().embeddingModel()).append('\n');
        builder.append("- llmModel: ").append(report.config().llmModel()).append('\n');
        builder.append("- judgeMode: ").append(report.config().judgeMode()).append("\n\n");
        builder.append("## Summary\n\n");
        builder.append("- totalQueries: ").append(summary.totalQueries()).append('\n');
        builder.append("- passedQueries: ").append(summary.passedQueries()).append('\n');
        builder.append("- answerableQueries: ").append(summary.answerableQueries()).append('\n');
        builder.append("- noAnswerQueries: ").append(summary.noAnswerQueries()).append('\n');
        builder.append("- recallAt3: ").append(summary.recallAt3()).append('\n');
        builder.append("- recallAt5: ").append(summary.recallAt5()).append('\n');
        builder.append("- recallAt10: ").append(summary.recallAt10()).append('\n');
        builder.append("- hitRateAt3: ").append(summary.hitRateAt3()).append('\n');
        builder.append("- hitRateAt5: ").append(summary.hitRateAt5()).append('\n');
        builder.append("- hitRateAt10: ").append(summary.hitRateAt10()).append('\n');
        builder.append("- mrr: ").append(summary.mrr()).append('\n');
        builder.append("- ndcgAt3: ").append(summary.ndcgAt3()).append('\n');
        builder.append("- ndcgAt5: ").append(summary.ndcgAt5()).append('\n');
        builder.append("- ndcgAt10: ").append(summary.ndcgAt10()).append('\n');
        builder.append("- correctness: ").append(summary.correctness()).append('\n');
        builder.append("- attribution: ").append(summary.attribution()).append('\n');
        builder.append("- completeness: ").append(summary.completeness()).append('\n');
        builder.append("- faithfulness: ").append(summary.faithfulness()).append('\n');
        builder.append("- readability: ").append(summary.readability()).append('\n');
        builder.append("- latencyP50Ms: ").append(summary.latencyP50Ms()).append('\n');
        builder.append("- latencyP95Ms: ").append(summary.latencyP95Ms()).append('\n');
        builder.append("- latencyP99Ms: ").append(summary.latencyP99Ms()).append("\n\n");
        builder.append("## Case Results\n\n");
        builder.append("| Case | Type | Answerable | Recall@3 | Hit@3 | MRR | nDCG@3 | Correctness | Faithfulness | NoAnswerMatched | LatencyMs | Passed | Note |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Stage8CaseResult result : report.caseResults()) {
            builder.append("| ")
                .append(result.caseId())
                .append(" | ")
                .append(result.queryType())
                .append(" | ")
                .append(result.answerable())
                .append(" | ")
                .append(result.retrievalMetrics().recallAt3())
                .append(" | ")
                .append(result.retrievalMetrics().hitRateAt3())
                .append(" | ")
                .append(result.retrievalMetrics().mrr())
                .append(" | ")
                .append(result.retrievalMetrics().ndcgAt3())
                .append(" | ")
                .append(result.generationScores().correctness())
                .append(" | ")
                .append(result.generationScores().faithfulness())
                .append(" | ")
                .append(result.noAnswerMatched())
                .append(" | ")
                .append(result.latencyMs())
                .append(" | ")
                .append(result.passed())
                .append(" | ")
                .append(result.note())
                .append(" |\n");
        }
        return builder.toString();
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").replace("|", "/").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220) + "...";
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : encoded) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    @TestConfiguration
    static class Stage8TestConfig {
        @Bean
        @Primary
        RedissonClient redissonClient() {
            RedissonClient client = mock(RedissonClient.class);
            RScript script = mock(RScript.class);
            when(client.getScript()).thenReturn(script);
            when(client.getScript(org.redisson.client.codec.StringCodec.INSTANCE)).thenReturn(script);
            when(script.scriptLoad(anyString())).thenReturn("stage8-noop-sha");
            return client;
        }

        @Bean
        @Primary
        RedisService redisService() {
            return new Stage8NoopRedisService();
        }
    }

    static class Stage8NoopRedisService extends RedisService {
        Stage8NoopRedisService() {
            super(mock(RedissonClient.class));
        }

        @Override
        public void createStreamGroup(String streamKey, String groupName) {
            // No-op: the Stage 8 RAG eval does not exercise Redis Stream task processing.
        }

        @Override
        public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            StreamMessageProcessor processor
        ) {
            try {
                Thread.sleep(Math.min(Math.max(blockTimeoutMs, 100), 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private record Stage8Dataset(
        String suiteId,
        String version,
        Stage8Corpus corpus,
        List<Stage8Query> queries
    ) {
    }

    private record Stage8Corpus(String source, int documentCount) {
    }

    private record Stage8Query(
        String id,
        String query,
        String queryType,
        boolean answerable,
        List<String> expectedSources,
        List<RelevanceJudgment> relevanceJudgments
    ) {
    }

    private record RelevanceJudgment(
        String docId,
        String section,
        int relevance,
        String reason
    ) {
    }

    private record ReferenceAnswerSet(
        String suiteId,
        String version,
        List<ReferenceAnswer> answers
    ) {
    }

    private record ReferenceAnswer(
        String queryId,
        String referenceAnswer,
        List<String> keyPoints,
        List<String> sourceDocuments
    ) {
    }

    private record Stage8Config(
        String datasetVersion,
        String corpus,
        String embeddingModel,
        String llmModel,
        List<Integer> topK,
        double minScore,
        String pipeline,
        String judgeMode
    ) {
    }

    private record RetrievalMetrics(
        double recallAt3,
        double recallAt5,
        double recallAt10,
        double hitRateAt3,
        double hitRateAt5,
        double hitRateAt10,
        double mrr,
        double ndcgAt3,
        double ndcgAt5,
        double ndcgAt10
    ) {
        static RetrievalMetrics empty() {
            return new RetrievalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record GenerationScores(
        double correctness,
        double attribution,
        double completeness,
        double faithfulness,
        double readability,
        String judgeMode
    ) {
        static GenerationScores empty() {
            return new GenerationScores(0, 0, 0, 0, 0, "none");
        }
    }

    private record HitSummary(
        String sourceTitle,
        String sectionTitle,
        Integer chunkIndex,
        String preview
    ) {
    }

    private record Stage8Summary(
        int totalQueries,
        int passedQueries,
        int answerableQueries,
        int noAnswerQueries,
        boolean skipped,
        double recallAt3,
        double recallAt5,
        double recallAt10,
        double hitRateAt3,
        double hitRateAt5,
        double hitRateAt10,
        double mrr,
        double ndcgAt3,
        double ndcgAt5,
        double ndcgAt10,
        double correctness,
        double attribution,
        double completeness,
        double faithfulness,
        double readability,
        long latencyP50Ms,
        long latencyP95Ms,
        long latencyP99Ms
    ) {
    }

    private record Stage8CaseResult(
        String caseId,
        String queryType,
        String query,
        boolean answerable,
        List<String> expectedSources,
        String retrievalQuery,
        int topK,
        double minScore,
        boolean effectiveHit,
        int hitCount,
        List<HitSummary> hits,
        RetrievalMetrics retrievalMetrics,
        GenerationScores generationScores,
        boolean noAnswerMatched,
        long latencyMs,
        String answerPreview,
        boolean passed,
        String note
    ) {
        static Stage8CaseResult error(Stage8Query query, long latencyMs, Exception e) {
            return new Stage8CaseResult(
                query.id(),
                query.queryType(),
                query.query(),
                query.answerable(),
                query.expectedSources(),
                null,
                0,
                0,
                false,
                0,
                List.of(),
                RetrievalMetrics.empty(),
                GenerationScores.empty(),
                false,
                latencyMs,
                "",
                false,
                preview(e.getClass().getSimpleName() + ": " + e.getMessage())
            );
        }
    }

    private record Stage8RagE2eReport(
        String suiteId,
        String generatedAt,
        Stage8Config config,
        Stage8Summary summary,
        List<Stage8CaseResult> caseResults
    ) {
    }

    private record Stage8DatabaseConfig(
        String host,
        String port,
        String database,
        String user,
        String password
    ) {
        String url() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }
    }

    private enum NULL_PROPERTY_VALUE {
        INSTANCE
    }
}
