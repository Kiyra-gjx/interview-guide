package interview.guide.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PromptSanitizer {

    private static final Pattern ROLE_INJECTION = Pattern.compile(
        "(?im)^\\s*(system|user|assistant|human)\\s*[:：].*"
    );

    private static final Pattern INJECTION_PHRASE = Pattern.compile(
        "(ignore\\s+(previous|above|all|your)\\s*(instructions|prompts|rules))" +
            "|(forget\\s+(everything|all\\s*(previous\\s*)?(instructions|rules|prompts)))" +
            "|(new\\s+instructions?:)" +
            "|忽略之前的指令|忘记之前的指令|忽略以上所有|你不再是|你的新角色是",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DELIMITER_INJECTION = Pattern.compile(
        "---(?:简历|文档|问答)内容(?:开始|结束)---"
    );

    private static final Pattern BOUNDARY_TAG = Pattern.compile(
        "</?data-boundary[^>]*>", Pattern.CASE_INSENSITIVE
    );

    private static final String ROLE_REPLACEMENT = "[已过滤：角色标记]";
    private static final String PHRASE_REPLACEMENT = "[已过滤：注入短语]";
    private static final String DELIMITER_REPLACEMENT = "[已过滤：分隔符]";
    private static final String BOUNDARY_REPLACEMENT = "[已过滤：边界标签]";

    @Value("${app.ai.advisors.prompt-sanitizer-enabled:true}")
    private boolean enabled;

    public String sanitize(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        // 顺序依赖：先处理边界标签伪造，再处理分隔符，最后处理角色/短语注入。
        // 确保攻击者无法用外层伪造标签包裹内层注入来绕过检测。
        String result = text;
        result = BOUNDARY_TAG.matcher(result).replaceAll(BOUNDARY_REPLACEMENT);
        result = DELIMITER_INJECTION.matcher(result).replaceAll(DELIMITER_REPLACEMENT);
        result = ROLE_INJECTION.matcher(result).replaceAll(ROLE_REPLACEMENT);
        result = INJECTION_PHRASE.matcher(result).replaceAll(PHRASE_REPLACEMENT);
        return result;
    }

    public String wrapWithDelimiters(String label, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String uuid8 = UUID.randomUUID().toString().substring(0, 8);
        String tag = "data-boundary-%s-%s".formatted(uuid8, label);
        return "<%s>\n%s\n</%s>".formatted(tag, text, tag);
    }

    public boolean detectInjectionAttempt(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return ROLE_INJECTION.matcher(text).find()
            || INJECTION_PHRASE.matcher(text).find()
            || DELIMITER_INJECTION.matcher(text).find()
            || BOUNDARY_TAG.matcher(text).find();
    }
}
