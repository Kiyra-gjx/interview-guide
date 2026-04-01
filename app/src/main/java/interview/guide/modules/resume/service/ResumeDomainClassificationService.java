package interview.guide.modules.resume.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ResumeDomainClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ResumeDomainClassificationService.class);

    private final ChatClient chatClient;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final PromptTemplate domainClassificationSystemPromptTemplate;
    private final PromptTemplate domainClassificationUserPromptTemplate;
    private final BeanOutputConverter<ResumeDomainClassificationDTO> domainClassificationOutputConverter;

    public ResumeDomainClassificationService(
        ChatClient.Builder chatClient,
        StructuredOutputInvoker structuredOutputInvoker,
        @Value("classpath:prompts/resume-domain-classification-system.st") Resource domainClassificationSystemPromptResource,
        @Value("classpath:prompts/resume-domain-classification-user.st") Resource domainClassificationUserPromptResource
    ) throws IOException {
        this.chatClient = chatClient.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.domainClassificationSystemPromptTemplate = new PromptTemplate(domainClassificationSystemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.domainClassificationUserPromptTemplate = new PromptTemplate(domainClassificationUserPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.domainClassificationOutputConverter = new BeanOutputConverter<>(ResumeDomainClassificationDTO.class);
    }

    private static final List<String> TECH_KEYWORDS = List.of(
        "java", "spring", "mysql", "redis", "linux", "docker",
        "git", "后端", "前端", "数据库", "计算机", "软件工程"
    );

    private static final List<String> OUT_OF_SCOPE_KEYWORDS = List.of(
        "法学", "法律", "律师", "律所", "法院", "检察院", "仲裁", "法务", "合规", "诉讼", "司法",
        "医学", "临床", "护理", "医生", "药学", "医药", "病房", "药房",
        "会计", "审计", "财务", "税务", "出纳", "注会",
        "教育", "教师", "教学", "教案", "班主任", "课程设计",
        "农业", "农学", "畜牧", "养殖", "水产", "园艺",
        "土木", "建筑学", "工程造价", "测绘", "结构设计",
        "人力资源", "招聘", "薪酬", "绩效", "员工关系",
        "新闻传播", "播音", "编导", "摄影", "视觉传达", "平面设计"
    );

    private record ResumeScope(
        boolean outOfScope,
        int techSignalCount,
        int domainSignalCount
    ) {
    }

    private record ResumeDomainClassificationDTO(
        String domain,
        String reason
    ) {
    }

    /**
     * 判断简历领域
     */
    public ResumeDomain classify(String resumeText) {
        // 1. 先判断规则明显领域外
        ResumeScope scope = assessResumeScope(resumeText);

        // 2. 否则走 AI 领域识别
        if (scope.outOfScope()) {
            return ResumeDomain.OUT_OF_SCOPE;
        }

        // 3. 返回 IN_COPE / OUT_OF_SCOPE / UNCERTAIN
        return classifyResumeDomain(resumeText);
    }

    /**
     * 兜底评估简历所属领域
     */
    private ResumeScope assessResumeScope(String resumeText) {
        String text = resumeText == null ? "" : resumeText.toLowerCase();

        int techSignalCount = countKeywords(text, TECH_KEYWORDS);
        int domainSignalCount = countKeywords(text, OUT_OF_SCOPE_KEYWORDS);

        boolean outOfScope = techSignalCount <= 1 && domainSignalCount >= 3;

        return new ResumeScope(outOfScope, techSignalCount, domainSignalCount);
    }

    /**
     * 计算领域关键词数量
     */
    private int countKeywords(String text, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    /**
     * AI 判断简历所属领域
     */
    private ResumeDomain classifyResumeDomain(String resumeText) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeText);

        String systemPrompt = domainClassificationSystemPromptTemplate.render();
        String userPrompt = domainClassificationUserPromptTemplate.render(variables);
        String systemPromptWithFormat = systemPrompt + "\n\n" + domainClassificationOutputConverter.getFormat();

        ResumeDomainClassificationDTO dto = structuredOutputInvoker.invoke(
            chatClient,
            systemPromptWithFormat,
            userPrompt,
            domainClassificationOutputConverter,
            ErrorCode.RESUME_ANALYSIS_FAILED,
            "简历领域识别失败",
            "简历领域识别",
            log
        );

        return parseResumeDomain(dto.domain());
    }

    /**
     * 解析简历所属领域
     */
    private ResumeDomain parseResumeDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return ResumeDomain.UNCERTAIN;
        }

        try {
            return ResumeDomain.valueOf(domain.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的简历领域分类结果：{}", domain);
            return ResumeDomain.UNCERTAIN;
        }
    }
}
