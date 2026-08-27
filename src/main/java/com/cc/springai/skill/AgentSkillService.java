package com.cc.springai.skill;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class AgentSkillService {

    private static final int MAX_CATALOG_DESCRIPTION_CHARS = 1536;
    private static final int MAX_SKILL_BODY_CHARS = 80_000;
    private static final int MAX_RESOURCE_CHARS = 100_000;
    private static final int MAX_SKILL_FILE_CHARS = 120_000;
    private static final Pattern SAFE_COMMAND_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,79}");
    private static final Pattern ARGUMENT_INDEX_PATTERN = Pattern.compile("\\$ARGUMENTS\\[(\\d+)]");
    private static final Pattern SHORT_ARGUMENT_INDEX_PATTERN = Pattern.compile("(?<!\\\\)\\$(\\d+)");

    public List<AgentSkillSummary> listSkills(String workingDirectory) {
        return discoverSkills(workingDirectory).stream()
                .map(AgentSkillSummary::from)
                .toList();
    }

    public String buildCatalogMessage(String workingDirectory) {
        List<AgentSkill> skills = discoverSkills(workingDirectory).stream()
                .filter(AgentSkill::modelInvocable)
                .toList();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder catalog = new StringBuilder("""
                ## 可用 Skills
                下面是当前工作目录可用的 Agent Skills。只有名称和描述已加载；当用户请求匹配某个 skill，或需要复用其中流程时，先调用 useSkill 读取完整说明，再按说明执行。用户也可以直接输入 /skill-name 调用。
                """);
        for (AgentSkill skill : skills) {
            catalog.append("- /")
                    .append(skill.commandName())
                    .append(": ")
                    .append(clip(skill.discoveryText(), MAX_CATALOG_DESCRIPTION_CHARS))
                    .append(" [")
                    .append(skill.source())
                    .append("]\n");
        }
        return catalog.toString().strip();
    }

    public Optional<RenderedSkill> renderDirectInvocation(String workingDirectory, String prompt) {
        Invocation invocation = parseInvocation(prompt);
        if (invocation == null) {
            return Optional.empty();
        }
        AgentSkill skill = skillByCommand(workingDirectory, invocation.commandName()).orElse(null);
        if (skill == null || !skill.userInvocable()) {
            return Optional.empty();
        }
        return Optional.of(render(skill, invocation.arguments()));
    }

    public Optional<RenderedSkill> renderSkill(String workingDirectory, String commandName, String arguments) {
        if (commandName == null || commandName.isBlank()) {
            return Optional.empty();
        }
        String normalizedName = commandName.strip();
        if (normalizedName.startsWith("/")) {
            normalizedName = normalizedName.substring(1);
        }
        String finalName = normalizedName;
        return skillByCommand(workingDirectory, finalName)
                .map(skill -> render(skill, arguments == null ? "" : arguments));
    }

    public AgentSkillFile readSkillFile(String workingDirectory, String commandName) {
        String normalized = normalizeCommandName(commandName);
        Optional<AgentSkill> existing = skillByCommand(workingDirectory, normalized);
        if (existing.isPresent()) {
            AgentSkill skill = existing.get();
            try {
                return new AgentSkillFile(
                        normalized,
                        skill.skillFile().toString(),
                        Files.readString(skill.skillFile(), StandardCharsets.UTF_8),
                        true,
                        skill.source().startsWith("project-"));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read skill file: " + e.getMessage(), e);
            }
        }
        Path target = defaultProjectSkillFile(workingDirectory, normalized);
        return new AgentSkillFile(normalized, target.toString(), "", false, true);
    }

    public AgentSkillFile writeSkillFile(String workingDirectory, String commandName, String content) {
        String normalized = normalizeCommandName(commandName);
        String safeContent = content == null ? "" : content;
        if (safeContent.length() > MAX_SKILL_FILE_CHARS) {
            throw new IllegalArgumentException("Skill file content is too large. Maximum characters: " + MAX_SKILL_FILE_CHARS);
        }
        Path projectRoot = projectRootFor(resolveWorkingDirectory(workingDirectory));
        Path target = editableSkillPath(workingDirectory, normalized, projectRoot);
        if (!target.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Skill file must stay inside the selected project.");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, safeContent, StandardCharsets.UTF_8);
            return new AgentSkillFile(normalized, target.toString(), safeContent, true, true);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write skill file: " + e.getMessage(), e);
        }
    }

    public boolean deleteSkill(String workingDirectory, String commandName) {
        String normalized = normalizeCommandName(commandName);
        AgentSkill skill = skillByCommand(workingDirectory, normalized)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + normalized));
        if (!skill.source().startsWith("project-")) {
            throw new IllegalArgumentException("Only project skills can be deleted from this workspace.");
        }
        Path projectRoot = projectRootFor(resolveWorkingDirectory(workingDirectory));
        if (!skill.skillFile().startsWith(projectRoot)) {
            throw new IllegalArgumentException("Skill file must stay inside the selected project.");
        }
        try {
            boolean deleted = Files.deleteIfExists(skill.skillFile());
            tryDeleteDirectoryIfEmpty(skill.directory());
            return deleted;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete skill: " + e.getMessage(), e);
        }
    }

    public String readResource(String workingDirectory, String commandName, String relativePath) {
        AgentSkill skill = renderSkill(workingDirectory, commandName, "").flatMap(rendered ->
                skillByCommand(workingDirectory, rendered.commandName())).orElse(null);
        if (skill == null) {
            return "Skill not found: " + commandName;
        }
        if (relativePath == null || relativePath.isBlank()) {
            return "Resource path cannot be empty.";
        }
        try {
            Path resource = skill.directory().resolve(relativePath).normalize();
            if (!resource.startsWith(skill.directory())) {
                return "Resource path escapes the skill directory.";
            }
            if (!Files.isRegularFile(resource)) {
                return "Resource does not exist or is not a regular file: " + relativePath;
            }
            String content = Files.readString(resource, StandardCharsets.UTF_8);
            return content.length() <= MAX_RESOURCE_CHARS
                    ? content
                    : content.substring(0, MAX_RESOURCE_CHARS) + "\n...resource truncated";
        } catch (Exception e) {
            return "Read skill resource failed: " + e.getMessage();
        }
    }

    List<AgentSkill> discoverSkills(String workingDirectory) {
        Map<String, AgentSkill> skills = new LinkedHashMap<>();
        for (SkillRoot root : skillRoots(workingDirectory)) {
            for (AgentSkill skill : scanRoot(root)) {
                AgentSkill current = skills.get(skill.commandName());
                if (current == null || skill.priority() >= current.priority()) {
                    skills.put(skill.commandName(), skill);
                }
            }
        }
        return skills.values().stream()
                .sorted(Comparator.comparing(AgentSkill::commandName))
                .toList();
    }

    private Optional<AgentSkill> skillByCommand(String workingDirectory, String commandName) {
        String normalized = commandName == null ? "" : commandName.strip();
        return discoverSkills(workingDirectory).stream()
                .filter(skill -> skill.commandName().equals(normalized))
                .findFirst();
    }

    private List<SkillRoot> skillRoots(String workingDirectory) {
        List<SkillRoot> roots = new ArrayList<>();
        Path cwd = resolveWorkingDirectory(workingDirectory);
        List<Path> projectDirectories = projectDirectories(cwd);
        int projectPriority = 10;
        for (Path directory : projectDirectories) {
            roots.add(new SkillRoot(directory.resolve(".agents").resolve("skills"), "project-agents", projectPriority));
            roots.add(new SkillRoot(directory.resolve(".claude").resolve("skills"), "project-claude", projectPriority + 1));
        }

        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        roots.add(new SkillRoot(home.resolve(".agents").resolve("skills"), "user-agents", 20));
        roots.add(new SkillRoot(home.resolve(".claude").resolve("skills"), "user-claude", 21));
        return roots;
    }

    private Path resolveWorkingDirectory(String workingDirectory) {
        Path applicationRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return applicationRoot;
        }
        Path requested = Path.of(workingDirectory);
        return requested.isAbsolute()
                ? requested.normalize()
                : applicationRoot.resolve(requested).normalize();
    }

    private Path projectRootFor(Path cwd) {
        Path current = cwd;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return cwd;
    }

    private Path defaultProjectSkillFile(String workingDirectory, String commandName) {
        Path projectRoot = projectRootFor(resolveWorkingDirectory(workingDirectory));
        return projectRoot.resolve(".claude").resolve("skills").resolve(commandName).resolve("SKILL.md").normalize();
    }

    private Path editableSkillPath(String workingDirectory, String commandName, Path projectRoot) {
        Optional<AgentSkill> existing = skillByCommand(workingDirectory, commandName);
        if (existing.isPresent() && existing.get().source().startsWith("project-")) {
            return existing.get().skillFile().normalize();
        }
        return projectRoot.resolve(".claude").resolve("skills").resolve(commandName).resolve("SKILL.md").normalize();
    }

    private String normalizeCommandName(String commandName) {
        String normalized = commandName == null ? "" : commandName.strip();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!SAFE_COMMAND_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Skill command name must match [A-Za-z0-9][A-Za-z0-9_-]{0,79}.");
        }
        return normalized;
    }

    private void tryDeleteDirectoryIfEmpty(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException ignored) {
        }
    }

    private List<Path> projectDirectories(Path cwd) {
        List<Path> directories = new ArrayList<>();
        Path current = cwd;
        while (current != null) {
            directories.add(current);
            if (Files.isDirectory(current.resolve(".git"))) {
                break;
            }
            current = current.getParent();
        }
        return directories;
    }

    private List<AgentSkill> scanRoot(SkillRoot root) {
        if (!Files.isDirectory(root.path())) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root.path())) {
            return entries
                    .filter(Files::isDirectory)
                    .map(directory -> loadSkill(directory, root))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Optional<AgentSkill> loadSkill(Path directory, SkillRoot root) {
        Path skillFile = directory.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(skillFile, StandardCharsets.UTF_8);
            SkillDocument document = parseSkillDocument(raw);
            String commandName = directory.getFileName().toString();
            String name = stringValue(document.frontmatter(), "name").orElse(commandName);
            String description = stringValue(document.frontmatter(), "description")
                    .or(() -> firstParagraph(document.body()))
                    .orElse("");
            if (description.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AgentSkill(
                    commandName,
                    name,
                    description,
                    stringValue(document.frontmatter(), "when_to_use").orElse(""),
                    booleanValue(document.frontmatter(), "disable-model-invocation", false),
                    booleanValue(document.frontmatter(), "user-invocable", true),
                    stringValue(document.frontmatter(), "argument-hint").orElse(""),
                    listValue(document.frontmatter(), "arguments"),
                    directory.toAbsolutePath().normalize(),
                    skillFile.toAbsolutePath().normalize(),
                    root.source(),
                    root.priority(),
                    clip(document.body(), MAX_SKILL_BODY_CHARS)
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private SkillDocument parseSkillDocument(String raw) {
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new SkillDocument(Map.of(), normalized.strip());
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return new SkillDocument(Map.of(), normalized.strip());
        }
        String frontmatterText = normalized.substring(4, end).strip();
        String body = normalized.substring(Math.min(normalized.length(), end + 4)).strip();
        return new SkillDocument(parseFrontmatter(frontmatterText), body);
    }

    private Map<String, Object> parseFrontmatter(String text) {
        Map<String, Object> values = new LinkedHashMap<>();
        String currentListKey = null;
        List<String> currentList = null;
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String stripped = line.strip();
            if (currentListKey != null && stripped.startsWith("- ")) {
                currentList.add(unquote(stripped.substring(2).strip()));
                continue;
            }
            currentListKey = null;
            currentList = null;
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (value.isEmpty()) {
                currentListKey = key;
                currentList = new ArrayList<>();
                values.put(key, currentList);
                continue;
            }
            values.put(key, parseScalarOrInlineList(value));
        }
        return values;
    }

    private Object parseScalarOrInlineList(String value) {
        String clean = stripComment(value).strip();
        if (clean.startsWith("[") && clean.endsWith("]")) {
            String inner = clean.substring(1, clean.length() - 1).strip();
            if (inner.isBlank()) {
                return List.of();
            }
            return Arrays.stream(inner.split(","))
                    .map(String::strip)
                    .map(this::unquote)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return unquote(clean);
    }

    private String stripComment(String value) {
        int index = value.indexOf(" #");
        return index < 0 ? value : value.substring(0, index);
    }

    private Optional<String> stringValue(Map<String, Object> frontmatter, String key) {
        Object value = frontmatter.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof List<?> list) {
            return Optional.of(list.stream()
                    .map(String::valueOf)
                    .reduce((left, right) -> left + " " + right)
                    .orElse(""));
        }
        return Optional.of(String.valueOf(value));
    }

    private boolean booleanValue(Map<String, Object> frontmatter, String key, boolean defaultValue) {
        return stringValue(frontmatter, key)
                .map(value -> "true".equalsIgnoreCase(value.strip()))
                .orElse(defaultValue);
    }

    private List<String> listValue(Map<String, Object> frontmatter, String key) {
        Object value = frontmatter.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Arrays.stream(text.split("[,\\s]+"))
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private Optional<String> firstParagraph(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(body.split("\\n\\s*\\n"))
                .map(String::strip)
                .filter(paragraph -> !paragraph.isBlank())
                .findFirst();
    }

    private RenderedSkill render(AgentSkill skill, String rawArguments) {
        String arguments = rawArguments == null ? "" : rawArguments.strip();
        List<String> tokens = splitArguments(arguments);
        String content = skill.body();
        content = content.replace("${CLAUDE_SKILL_DIR}", skill.directory().toString());
        content = replaceIndexedArguments(content, ARGUMENT_INDEX_PATTERN, tokens);
        content = replaceNamedArguments(content, skill.arguments(), tokens);
        content = replaceIndexedArguments(content, SHORT_ARGUMENT_INDEX_PATTERN, tokens);
        content = content.replace("$ARGUMENTS", arguments);
        if (!content.contains("ARGUMENTS:") && !arguments.isBlank() && !skill.body().contains("$ARGUMENTS")) {
            content = content + "\n\nARGUMENTS: " + arguments;
        }
        String rendered = """
                ## Skill: /%s
                Source: %s
                Path: %s

                %s
                """.formatted(skill.commandName(), skill.source(), skill.directory(), content).strip();
        return new RenderedSkill(skill.commandName(), arguments, rendered, skill.source(), skill.directory().toString());
    }

    private String replaceIndexedArguments(String content, Pattern pattern, List<String> tokens) {
        Matcher matcher = pattern.matcher(content);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = index < tokens.size() ? tokens.get(index) : "";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String replaceNamedArguments(String content, List<String> names, List<String> tokens) {
        String rendered = content;
        for (int index = 0; index < names.size(); index++) {
            String value = index < tokens.size() ? tokens.get(index) : "";
            rendered = rendered.replace("$" + names.get(index), value);
        }
        return rendered;
    }

    private List<String> splitArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(arguments);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                tokens.add(matcher.group(2));
            } else {
                tokens.add(matcher.group(3));
            }
        }
        return tokens;
    }

    private Invocation parseInvocation(String prompt) {
        if (prompt == null) {
            return null;
        }
        String stripped = prompt.strip();
        if (!stripped.startsWith("/") || stripped.length() == 1) {
            return null;
        }
        int firstWhitespace = firstWhitespace(stripped);
        String commandName = firstWhitespace < 0 ? stripped.substring(1) : stripped.substring(1, firstWhitespace);
        if (commandName.isBlank() || commandName.contains("/")) {
            return null;
        }
        String arguments = firstWhitespace < 0 ? "" : stripped.substring(firstWhitespace + 1).strip();
        return new Invocation(commandName, arguments);
    }

    private int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private String clip(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "\n...truncated";
    }

    private String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value;
        }
        String trimmed = value.strip();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record SkillRoot(Path path, String source, int priority) {
    }

    private record SkillDocument(Map<String, Object> frontmatter, String body) {
    }

    private record Invocation(String commandName, String arguments) {
    }
}
