package cn.bugstack.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

@Getter
@AllArgsConstructor
public enum OutputModeEnum {

    ALL_EVENTS("ALL_EVENTS", "输出所有 Agent 事件"),
    FINAL_ONLY("FINAL_ONLY", "只返回最终响应 Agent 的答案"),
    FINAL_WITH_TRACE("FINAL_WITH_TRACE", "返回最终答案，并保留子 Agent 的最终产出作为调研轨迹"),
    ;

    private final String code;
    private final String description;

    public static Optional<OutputModeEnum> fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return Optional.empty();
        }

        String normalizedCode = code.trim();
        return Arrays.stream(values())
                .filter(mode -> mode.code.equalsIgnoreCase(normalizedCode))
                .findFirst();
    }
}
