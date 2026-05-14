package interview.guide.common.ai;

import org.springframework.stereotype.Component;

/**
 * 处理不同 LLM 厂商的 API 路径差异。
 * 部分厂商（如 DashScope）使用 OpenAI 兼容模式但路径可能不同。
 */
@Component
public class ApiPathResolver {

    public String resolveChatPath(String baseUrl) {
        if (baseUrl == null) {
            return "/v1";
        }
        if (baseUrl.contains("dashscope.aliyuncs.com")) {
            return "/compatible-mode";
        }
        return "/v1";
    }

    public String resolveEmbeddingPath(String baseUrl) {
        if (baseUrl == null) {
            return "/v1";
        }
        if (baseUrl.contains("dashscope.aliyuncs.com")) {
            return "/compatible-mode";
        }
        return "/v1";
    }

    public String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return baseUrl;
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1") || normalized.endsWith("/compatible-mode")) {
            return normalized;
        }
        return normalized;
    }
}
