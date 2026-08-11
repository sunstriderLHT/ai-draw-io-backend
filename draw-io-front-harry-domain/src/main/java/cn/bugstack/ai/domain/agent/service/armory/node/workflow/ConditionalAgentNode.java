package cn.bugstack.ai.domain.agent.service.armory.node.workflow;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service("conditionalAgentNode")
public class ConditionalAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(
            ArmoryCommandEntity requestParameter,
            DefaultArmoryFactory.DynamicContext dynamicContext
    ) throws Exception {
        log.info("Ai Agent assembly - ConditionalAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow agentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(agentWorkflow.getSubAgents());
        ConditionalAgent conditionalAgent = buildAgent(agentWorkflow, subAgents);

        dynamicContext.getAgentGroup().put(agentWorkflow.getName(), conditionalAgent);
        return router(requestParameter, dynamicContext);
    }

    ConditionalAgent buildAgent(
            AiAgentConfigTableVO.Module.AgentWorkflow workflow,
            List<? extends BaseAgent> subAgents
    ) {
        requireText(workflow.getName(), "conditional workflow name");
        requireText(workflow.getConditionStateKey(), "condition-state-key");
        requireText(workflow.getConditionJsonField(), "condition-json-field");
        requireText(workflow.getConditionExpectedValue(), "condition-expected-value");
        if (subAgents == null || subAgents.isEmpty()) {
            throw new IllegalArgumentException("conditional workflow must resolve at least one sub-agent");
        }

        return new ConditionalAgent(
                workflow.getName(),
                workflow.getDescription(),
                subAgents,
                workflow.getConditionStateKey(),
                workflow.getConditionJsonField(),
                workflow.getConditionExpectedValue()
        );
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(
            ArmoryCommandEntity requestParameter,
            DefaultArmoryFactory.DynamicContext dynamicContext
    ) throws Exception {
        return getBean("agentWorkflowNode");
    }
}
