package interview.guide.modules.agent.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 输出的统一结构化视图。
 */
public record AgentToolOutputDTO(
    String kind,
    String summary,
    String reply,
    Map<String, Object> answer,
    Map<String, Object> debug,
    List<String> facts,
    AgentToolOutputNormalizationDTO normalization
) {

    public AgentToolOutputDTO {
        answer = answer == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(answer));
        debug = debug == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(debug));
        facts = facts == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(facts));
        normalization = normalization == null
            ? new AgentToolOutputNormalizationDTO(false, false, false, false)
            : normalization;
    }
}
