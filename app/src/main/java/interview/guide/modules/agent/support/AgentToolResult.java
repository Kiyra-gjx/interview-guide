package interview.guide.modules.agent.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 执行结果。
 */
public record AgentToolResult(
    String summary,
    Map<String, Object> answerPayload,
    Map<String, Object> debugPayload,
    List<String> confirmedFacts
) {

    public AgentToolResult {
        answerPayload = answerPayload == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(answerPayload));
        debugPayload = debugPayload == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(debugPayload));
        confirmedFacts = confirmedFacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(confirmedFacts));
    }

    public Map<String, Object> tracePayload() {
        return tracePayload(null);
    }

    public Map<String, Object> tracePayload(String reply) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "tool_result");
        payload.put("summary", summary);
        payload.put("reply", reply);
        payload.put("answerPayload", answerPayload);
        payload.put("debugPayload", debugPayload);
        payload.put("confirmedFacts", confirmedFacts);
        return Collections.unmodifiableMap(payload);
    }
}
