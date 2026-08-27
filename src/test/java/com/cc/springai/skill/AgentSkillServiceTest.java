package com.cc.springai.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSkillServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversProjectClaudeSkillsAndBuildsCatalog() throws Exception {
        Path skillDir = tempDir.resolve(".claude").resolve("skills").resolve("review-pr");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: review-pr
                description: Review pull requests for regressions. Use when the user asks to review a PR.
                ---

                Check the diff and report findings first.
                """);

        AgentSkillService service = new AgentSkillService();

        assertThat(service.listSkills(tempDir.toString()))
                .extracting(AgentSkillSummary::commandName)
                .contains("review-pr");
        assertThat(service.buildCatalogMessage(tempDir.toString()))
                .contains("/review-pr")
                .contains("Review pull requests");
    }

    @Test
    void rendersDirectInvocationWithArguments() throws Exception {
        Path skillDir = tempDir.resolve(".claude").resolve("skills").resolve("fix-issue");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: fix-issue
                description: Fix a GitHub issue.
                arguments: [issue]
                ---

                Fix issue $issue.
                Raw: $ARGUMENTS
                """);

        AgentSkillService service = new AgentSkillService();

        RenderedSkill rendered = service.renderDirectInvocation(tempDir.toString(), "/fix-issue 123")
                .orElseThrow();

        assertThat(rendered.commandName()).isEqualTo("fix-issue");
        assertThat(rendered.content())
                .contains("Fix issue 123.")
                .contains("Raw: 123");
    }

    @Test
    void modelDisabledSkillIsHiddenFromCatalogButCanBeUserInvoked() throws Exception {
        Path skillDir = tempDir.resolve(".claude").resolve("skills").resolve("deploy");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: deploy
                description: Deploy the application.
                disable-model-invocation: true
                ---

                Deploy carefully.
                """);

        AgentSkillService service = new AgentSkillService();

        assertThat(service.buildCatalogMessage(tempDir.toString())).doesNotContain("/deploy");
        assertThat(service.renderDirectInvocation(tempDir.toString(), "/deploy staging")).isPresent();
    }

    @Test
    void rendersIndexedArgumentsBeforeFullArguments() throws Exception {
        Path skillDir = tempDir.resolve(".claude").resolve("skills").resolve("migrate-component");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: migrate-component
                description: Migrate a component between frameworks.
                ---

                Migrate $ARGUMENTS[0] from $1 to $2.
                Full: $ARGUMENTS
                """);

        AgentSkillService service = new AgentSkillService();

        RenderedSkill rendered = service.renderDirectInvocation(
                tempDir.toString(), "/migrate-component SearchBar React Vue").orElseThrow();

        assertThat(rendered.content())
                .contains("Migrate SearchBar from React to Vue.")
                .contains("Full: SearchBar React Vue");
    }

    @Test
    void writesAndDeletesProjectSkillFiles() throws Exception {
        Files.createDirectories(tempDir.resolve(".git"));
        AgentSkillService service = new AgentSkillService();
        String content = """
                ---
                name: draft-release
                description: Draft a release note.
                ---

                Write release notes from the current diff.
                """;

        AgentSkillFile saved = service.writeSkillFile(tempDir.toString(), "draft-release", content);

        assertThat(saved.exists()).isTrue();
        assertThat(saved.editable()).isTrue();
        assertThat(Files.readString(tempDir.resolve(".claude/skills/draft-release/SKILL.md"))).contains("Draft a release note");
        assertThat(service.listSkills(tempDir.toString()))
                .extracting(AgentSkillSummary::commandName)
                .contains("draft-release");

        assertThat(service.deleteSkill(tempDir.toString(), "draft-release")).isTrue();
        assertThat(Files.exists(tempDir.resolve(".claude/skills/draft-release/SKILL.md"))).isFalse();
    }
}
