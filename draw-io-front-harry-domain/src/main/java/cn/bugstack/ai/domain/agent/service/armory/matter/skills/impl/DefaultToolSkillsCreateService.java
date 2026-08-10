package cn.bugstack.ai.domain.agent.service.armory.matter.skills.impl;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {
        String type = toolSkills.getType();
        String path = toolSkills.getPath();
        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if ("directory".equals(type)) {
            toolCallbackList.add(SkillsTool.builder()
                    .addSkillsDirectory(path)
                    .build());

        }
        if ("resource".equals(type)) {
            toolCallbackList.add(SkillsTool.builder()
                    .addSkillsResource(new ClassPathResource(path))
                    .build());
        }
        return toolCallbackList.toArray(new ToolCallback[0]);
    }
}
