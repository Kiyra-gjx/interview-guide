package interview.guide.modules.interview.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 面试问题生成服务
 * 基于简历内容生成针对性的面试问题
 */
@Service
public class InterviewQuestionService {
    
    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);
    
    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int followUpCount;
    
    // 问题类型权重分配（按优先级）
    private static final double PROJECT_RATIO = 0.20;      // 20% 项目经历
    private static final double MYSQL_RATIO = 0.20;        // 20% MySQL
    private static final double REDIS_RATIO = 0.20;        // 20% Redis
    private static final double JAVA_BASIC_RATIO = 0.10;   // 10% Java基础
    private static final double JAVA_COLLECTION_RATIO = 0.10; // 10% 集合
    private static final double JAVA_CONCURRENT_RATIO = 0.10; // 10% 并发
    private static final int MAX_FOLLOW_UP_COUNT = 2;
    
    // 中间DTO用于接收AI响应
    private record QuestionListDTO(
        List<QuestionDTO> questions
    ) {}
    
    private record QuestionDTO(
        String question,
        String type,
        String category,
        List<String> followUps
    ) {}
    
    /**
     * 初始化题目生成所需的模型客户端、提示词模板和追问配置。
     */
    public InterviewQuestionService(
            ChatClient.Builder chatClientBuilder,
            StructuredOutputInvoker structuredOutputInvoker,
            @Value("classpath:prompts/interview-question-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/interview-question-user.st") Resource userPromptResource,
            @Value("${app.interview.follow-up-count:1}") int followUpCount) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpCount = Math.max(0, Math.min(followUpCount, MAX_FOLLOW_UP_COUNT));
    }
    
    /**
     * 生成面试问题
     * 
     * @param resumeText 简历文本
     * @param questionCount 问题数量
     * @param historicalQuestions 历史问题列表（可选）
     * @return 面试问题列表
     */
    public List<InterviewQuestionDTO> generateQuestions(String resumeText, int questionCount, List<String> historicalQuestions) {
        log.info("开始生成面试问题，简历长度: {}, 问题数量: {}, 历史问题数: {}", 
            resumeText.length(), questionCount, historicalQuestions != null ? historicalQuestions.size() : 0);
        
        // 计算各类型问题数量
        // 1. 先根据总题数估算各类题目的分布比例，避免题型过于单一。
        QuestionDistribution distribution = calculateDistribution(questionCount);
        
        try {
            // 加载系统提示词
            // 2. 组装系统提示词和用户提示词，把简历、题量、历史问题一并交给模型。
            String systemPrompt = systemPromptTemplate.render();
            
            // 加载用户提示词并填充变量
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("projectCount", distribution.project);
            variables.put("mysqlCount", distribution.mysql);
            variables.put("redisCount", distribution.redis);
            variables.put("javaBasicCount", distribution.javaBasic);
            variables.put("javaCollectionCount", distribution.javaCollection);
            variables.put("javaConcurrentCount", distribution.javaConcurrent);
            variables.put("springCount", distribution.spring);
            variables.put("followUpCount", followUpCount);
            variables.put("resumeText", resumeText);
            
            // 添加历史问题
            // 2.1 历史问题用于做去重和多样性约束。
            if (historicalQuestions != null && !historicalQuestions.isEmpty()) {
                String historicalText = String.join("\n", historicalQuestions);
                variables.put("historicalQuestions", historicalText);
            } else {
                variables.put("historicalQuestions", "暂无历史提问");
            }
            
            String userPrompt = userPromptTemplate.render(variables);
            
            // 添加格式指令到系统提示词
            // 3. 追加结构化输出格式约束，减少模型自由发挥导致的解析失败。
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
            
            // 调用AI
            // 4. 调用模型生成题目；如果结构化解析失败，交给外层统一降级。
            QuestionListDTO dto;
            try {
                dto = structuredOutputInvoker.invoke(
                    chatClient,
                    systemPromptWithFormat,
                    userPrompt,
                    outputConverter,
                    ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "面试问题生成失败：",
                    "结构化问题生成",
                    log
                );
                log.debug("AI响应解析成功: questions count={}", dto.questions().size());
            } catch (Exception e) {
                log.error("面试问题生成AI调用失败: {}", e.getMessage(), e);
                throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, 
                    "面试问题生成失败：" + e.getMessage());
            }
            
            // 转换为业务对象
            // 5. 把模型 DTO 转成业务对象，并展开追问题。
            List<InterviewQuestionDTO> questions = convertToQuestions(dto);
            log.info("成功生成 {} 个面试问题", questions.size());
            
            return questions;
            
        } catch (Exception e) {
            log.error("生成面试问题失败: {}", e.getMessage(), e);
            // 返回默认问题集
            // 6. 任意异常都回退到默认题库，保证面试流程可继续。
            return generateDefaultQuestions(questionCount);
        }
    }

    /**
     * 生成面试问题（不带历史问题）
     */
    public List<InterviewQuestionDTO> generateQuestions(String resumeText, int questionCount) {
        return generateQuestions(resumeText, questionCount, null);
    }
    
    /**
     * 计算各类型问题分布
     */
    private QuestionDistribution calculateDistribution(int total) {
        int project = Math.max(1, (int) Math.round(total * PROJECT_RATIO));
        int mysql = Math.max(1, (int) Math.round(total * MYSQL_RATIO));
        int redis = Math.max(1, (int) Math.round(total * REDIS_RATIO));
        int javaBasic = Math.max(1, (int) Math.round(total * JAVA_BASIC_RATIO));
        int javaCollection = (int) Math.round(total * JAVA_COLLECTION_RATIO);
        int javaConcurrent = (int) Math.round(total * JAVA_CONCURRENT_RATIO);
        int spring = total - project - mysql - redis - javaBasic - javaCollection - javaConcurrent;
        
        // 确保至少有1个
        spring = Math.max(0, spring);
        
        return new QuestionDistribution(project, mysql, redis, javaBasic, javaCollection, javaConcurrent, spring);
    }
    
    private record QuestionDistribution(
        int project, int mysql, int redis, 
        int javaBasic, int javaCollection, int javaConcurrent, int spring
    ) {}
    
    /**
     * 转换DTO为业务对象
     */
    private List<InterviewQuestionDTO> convertToQuestions(QuestionListDTO dto) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (dto == null || dto.questions() == null) {
            return questions;
        }

        // 1. 先落主问题，再按配置展开有限数量的追问题。
        for (QuestionDTO q : dto.questions()) {
            if (q == null || q.question() == null || q.question().isBlank()) {
                continue;
            }
            QuestionType type = parseQuestionType(q.type());
            int mainQuestionIndex = index;
            questions.add(InterviewQuestionDTO.create(index++, q.question(), type, q.category(), false, null));

            List<String> followUps = sanitizeFollowUps(q.followUps());
            for (int i = 0; i < followUps.size(); i++) {
                questions.add(InterviewQuestionDTO.create(
                    index++,
                    followUps.get(i),
                    type,
                    buildFollowUpCategory(q.category(), i + 1),
                    true,
                    mainQuestionIndex
                ));
            }
        }
        
        return questions;
    }
    
    /**
     * 解析模型返回的题目类型，异常时回退到 Java 基础题。
     */
    private QuestionType parseQuestionType(String typeStr) {
        try {
            return QuestionType.valueOf(typeStr.toUpperCase());
        } catch (Exception e) {
            return QuestionType.JAVA_BASIC;
        }
    }
    
    /**
     * 生成默认问题（备用）
     */
    private List<InterviewQuestionDTO> generateDefaultQuestions(int count) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        
        // 默认题库用于 AI 失败时兜底，覆盖项目、数据库、集合、并发等核心面试主题。
        String[][] defaultQuestions = {
            {"请介绍一下你在简历中提到的最重要的项目，你在其中承担了什么角色？", "PROJECT", "项目经历"},
            {"MySQL的索引有哪些类型？B+树索引的原理是什么？", "MYSQL", "MySQL"},
            {"Redis支持哪些数据结构？各自的使用场景是什么？", "REDIS", "Redis"},
            {"Java中HashMap的底层实现原理是什么？JDK8做了哪些优化？", "JAVA_COLLECTION", "Java集合"},
            {"synchronized和ReentrantLock有什么区别？", "JAVA_CONCURRENT", "Java并发"},
            {"Spring的IoC和AOP原理是什么？", "SPRING", "Spring"},
            {"MySQL事务的ACID特性是什么？隔离级别有哪些？", "MYSQL", "MySQL"},
            {"Redis的持久化机制有哪些？RDB和AOF的区别？", "REDIS", "Redis"},
            {"Java的垃圾回收机制是怎样的？常见的GC算法有哪些？", "JAVA_BASIC", "Java基础"},
            {"线程池的核心参数有哪些？如何合理配置？", "JAVA_CONCURRENT", "Java并发"},
        };
        
        int index = 0;
        for (int i = 0; i < Math.min(count, defaultQuestions.length); i++) {
            // 1. 先创建主问题。
            String mainQuestion = defaultQuestions[i][0];
            QuestionType type = QuestionType.valueOf(defaultQuestions[i][1]);
            String category = defaultQuestions[i][2];
            questions.add(InterviewQuestionDTO.create(
                index++,
                mainQuestion,
                type,
                category,
                false,
                null
            ));

            int mainQuestionIndex = index - 1;
            // 2. 再根据配置补充默认追问，保持前后端题型结构一致。
            for (int j = 0; j < followUpCount; j++) {
                questions.add(InterviewQuestionDTO.create(
                    index++,
                    buildDefaultFollowUp(mainQuestion, j + 1),
                    type,
                    buildFollowUpCategory(category, j + 1),
                    true,
                    mainQuestionIndex
                ));
            }
        }
        
        return questions;
    }

    /**
     * 清洗模型生成的追问列表，并截断到允许的最大数量。
     */
    private List<String> sanitizeFollowUps(List<String> followUps) {
        if (followUpCount == 0 || followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .limit(followUpCount)
            .collect(Collectors.toList());
    }

    /**
     * 生成追问题所属的展示分类。
     */
    private String buildFollowUpCategory(String category, int order) {
        String baseCategory = (category == null || category.isBlank()) ? "追问" : category;
        return baseCategory + "（追问" + order + "）";
    }

    /**
     * 构造默认追问文案，在降级题库场景下保持追问体验。
     */
    private String buildDefaultFollowUp(String mainQuestion, int order) {
        if (order == 1) {
            return "基于“" + mainQuestion + "”，请结合你亲自做过的一个真实场景展开说明。";
        }
        return "基于“" + mainQuestion + "”，如果线上出现异常，你会如何定位并给出修复方案？";
    }
}
