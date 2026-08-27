package com.cc.springai.tools;

import com.cc.springai.skill.AgentSkillService;
import com.cc.springai.skill.AgentSkillSummary;
import com.cc.springai.skill.RenderedSkill;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SkillsTools {

    private final AgentSkillService skillService;

    public SkillsTools(AgentSkillService skillService) {
        this.skillService = skillService;
    }

    @Tool(description = "List available Agent Skills for the selected working directory. This is read-only.")
    public List<AgentSkillSummary> listSkills(ToolContext toolContext) {
        return skillService.listSkills(workingDirectory(toolContext));
    }

    @Tool(description = "Load the full instructions for an Agent Skill by command name. Call this before following a relevant skill. This is read-only.")
    public String useSkill(
            @ToolParam(description = "Skill command name, with or without leading slash") String commandName,
            @ToolParam(description = "Arguments to pass to the skill, if any", required = false) String arguments,
            ToolContext toolContext) {
        Optional<RenderedSkill> rendered = skillService.renderSkill(workingDirectory(toolContext), commandName, arguments);
        return rendered.map(RenderedSkill::content)
                .orElse("Skill not found: " + commandName);
    }

    @Tool(description = "Read a file bundled inside a skill directory, such as references or assets. This is read-only.")
    public String readSkillResource(
            @ToolParam(description = "Skill command name, with or without leading slash") String commandName,
            @ToolParam(description = "Relative path inside the skill directory") String relativePath,
            ToolContext toolContext) {
        return skillService.readResource(workingDirectory(toolContext), commandName, relativePath);
    }

    private String workingDirectory(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(BasicTools.WORKING_DIRECTORY_CONTEXT_KEY);
        return value instanceof String text ? text : "";
    }
}
