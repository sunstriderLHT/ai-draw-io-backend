package cn.bugstack.ai.domain.agent.service.armory.node.workflow;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.OutputModeEnum;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AgentOutputPolicyValidator {

    public OutputModeEnum validate(
            AiAgentConfigTableVO.Module.Runner runnerConfig,
            Set<String> availableAgentNames) {
        String configuredOutputMode = runnerConfig.getOutputMode();
        OutputModeEnum outputMode = StringUtils.isBlank(configuredOutputMode)
                ? OutputModeEnum.ALL_EVENTS
                : OutputModeEnum.fromCode(configuredOutputMode)
                .orElseThrow(() -> illegalParameter(
                        "Unsupported output-mode: " + configuredOutputMode));

        runnerConfig.setOutputMode(outputMode.getCode());

        if (OutputModeEnum.ALL_EVENTS == outputMode) {
            return outputMode;
        }

        String responseAgentName = runnerConfig.getResponseAgentName();
        if (StringUtils.isBlank(responseAgentName)) {
            throw illegalParameter(
                    "response-agent-name is required for output-mode " + outputMode.getCode());
        }

        if (!availableAgentNames.contains(responseAgentName)) {
            throw illegalParameter(
                    "Unknown response-agent-name: " + responseAgentName);
        }

        return outputMode;
    }

    private AppException illegalParameter(String message) {
        return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), message);
    }
}
