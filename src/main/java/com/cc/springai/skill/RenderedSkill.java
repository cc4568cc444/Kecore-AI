package com.cc.springai.skill;

public record RenderedSkill(
        String commandName,
        String arguments,
        String content,
        String source,
        String path) {
}
