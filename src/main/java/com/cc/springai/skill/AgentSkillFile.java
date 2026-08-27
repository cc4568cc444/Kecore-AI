package com.cc.springai.skill;

public record AgentSkillFile(
        String commandName,
        String path,
        String content,
        boolean exists,
        boolean editable) {
}
