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
    Map<String, Object> output,
    List<String> confirmedFacts
) {

    public AgentToolResult {
        output = output == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(output));
        confirmedFacts = confirmedFacts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(confirmedFacts));
    }
}
