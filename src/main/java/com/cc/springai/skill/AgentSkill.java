package com.cc.springai.skill;

import java.nio.file.Path;
import java.util.List;

public record AgentSkill(
        String commandName,
        String name,
        String description,
        String whenToUse,
        boolean disableModelInvocation,
        boolean userInvocable,
        String argumentHint,
        List<String> arguments,
        Path directory,
        Path skillFile,
        String source,
        int priority,
        String body) {

    public boolean modelInvocable() {
        return !disableModelInvocation;
    }

    public String discoveryText() {
        StringBuilder text = new StringBuilder();
        text.append(description == null ? "" : description.strip());
        if (whenToUse != null && !whenToUse.isBlank()) {
            if (!text.isEmpty()) {
                text.append(" ");
            }
            text.append(whenToUse.strip());
        }
        return text.toString().strip();
    }
}
