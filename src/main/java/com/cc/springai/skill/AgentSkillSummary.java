package com.cc.springai.skill;

public record AgentSkillSummary(
        String commandName,
        String name,
        String description,
        String whenToUse,
        boolean modelInvocable,
        boolean userInvocable,
        String source,
        String path) {

    static AgentSkillSummary from(AgentSkill skill) {
        return new AgentSkillSummary(
                skill.commandName(),
                skill.name(),
                skill.description(),
                skill.whenToUse(),
                skill.modelInvocable(),
                skill.userInvocable(),
                skill.source(),
                skill.directory().toString());
    }
}
