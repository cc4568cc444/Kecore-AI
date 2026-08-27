package com.cc.springai.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class MemoryService {

    public static final List<String> MEMORY_TYPES = List.of("user", "feedback", "project", "reference");

    private static final int MAX_INDEX_LINES = 200;
    private static final int MAX_INDEX_CHARS = 25 * 1024;
    private static final int MAX_RULE_FILE_CHARS = 24_000;
    private static final int MAX_WRITE_CHARS = 80_000;
    private static final String MEMORY_INDEX_FILE_NAME = "MEMORY.md";
    private static final String MEMORY_DIRECTORY_NAME = "memory";
    private static final Pattern SAFE_MEMORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,79}");
    private static final DateTimeFormatter MEMORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path applicationRoot;

    public MemoryService() {
        this(Path.of(System.getProperty("user.dir")));
    }

    MemoryService(Path applicationRoot) {
        this.applicationRoot = applicationRoot.toAbsolutePath().normalize();
    }

    public String loadPromptMemory(String workingDirectory) {
        StringBuilder context = new StringBuilder();
        for (MemoryFile file : listRuleFiles(workingDirectory)) {
            if (!file.exists() || file.content().isBlank()) {
                continue;
            }
            context.append("### ")
                    .append(file.label())
                    .append(" (")
                    .append(file.relativePath())
                    .append(")\n")
                    .append(truncate(file.content(), MAX_RULE_FILE_CHARS))
                    .append("\n\n");
        }

        Path root = resolveGlobalMemoryRoot();
        Path indexPath = root.resolve(MEMORY_INDEX_FILE_NAME).normalize();
        ensureInsideRoot(root, indexPath);
        if (Files.isRegularFile(indexPath)) {
            try {
                String index = readMemoryIndexForPrompt(indexPath);
                if (!index.isBlank()) {
                    context.append("### Memory index (MEMORY.md)\n")
                            .append(index)
                            .append("\n\n");
                }
            } catch (IOException ignored) {
            }
        }

        if (context.isEmpty()) {
            return "";
        }
        return """
                ## Long-Term Memory Context
                The following content comes from project rule files and MEMORY.md. Treat it as durable context, but current user instructions override it.

                %s
                """.formatted(context.toString().trim());
    }

    public List<MemoryFile> listMemoryFiles(String workingDirectory) {
        Path workspaceRoot = resolveWorkingDirectory(workingDirectory);
        Path globalRoot = resolveGlobalMemoryRoot();
        List<MemoryFile> files = new ArrayList<>();
        files.add(readMemoryFile(workspaceRoot, "project", "Project rules", "AGENT.md"));
        files.add(readMemoryFile(workspaceRoot, "local", "Local rules", "AGENT.local.md"));
        files.add(readMemoryFile(workspaceRoot, "agent", "Agent rules", ".agent/AGENT.md"));
        files.add(readMemoryFile(globalRoot, "memory-index", "Memory index", MEMORY_INDEX_FILE_NAME));
        files.addAll(listStoredMemoryFiles(globalRoot));
        return files;
    }

    private List<MemoryFile> listRuleFiles(String workingDirectory) {
        Path workspaceRoot = resolveWorkingDirectory(workingDirectory);
        List<MemoryFile> files = new ArrayList<>();
        files.add(readMemoryFile(workspaceRoot, "project", "Project rules", "AGENT.md"));
        files.add(readMemoryFile(workspaceRoot, "local", "Local rules", "AGENT.local.md"));
        files.add(readMemoryFile(workspaceRoot, "agent", "Agent rules", ".agent/AGENT.md"));
        return files;
    }

    public MemoryFile writeMemoryFile(String workingDirectory, String fileId, String content) {
        if (content != null && content.length() > MAX_WRITE_CHARS) {
            throw new IllegalArgumentException("Memory file content is too large. Maximum characters: " + MAX_WRITE_CHARS);
        }
        Path root = rootForMemoryFile(workingDirectory, fileId);
        String relativePath = relativePathFor(fileId);
        Path target = root.resolve(relativePath).normalize();
        ensureInsideRoot(root, target);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
            return readMemoryFile(root, fileId, labelFor(fileId), relativePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write memory file: " + e.getMessage(), e);
        }
    }

    public String saveMemory(String workingDirectory, String name, String description, String type,
                             String opportunity, String content) {
        String normalizedType = normalizeType(type);
        String normalizedName = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        String normalizedOpportunity = normalizeOpportunity(opportunity);
        String normalizedContent = normalizeContent(content);
        String savedAt = nowAsMinute();

        Path root = resolveGlobalMemoryRoot();
        Path memoryPath = memoryPath(root, normalizedType, normalizedName);
        Path indexPath = root.resolve(MEMORY_INDEX_FILE_NAME).normalize();
        ensureInsideRoot(root, memoryPath);
        ensureInsideRoot(root, indexPath);

        String fileContent = """
                ---
                name: %s
                description: %s
                type: %s
                saved_at: %s
                opportunity: %s
                ---
                %s
                """.formatted(normalizedName, normalizedDescription, normalizedType, savedAt, normalizedOpportunity,
                normalizedContent);

        if (fileContent.length() > MAX_WRITE_CHARS) {
            return "Memory content is too large. Please keep each memory below " + MAX_WRITE_CHARS + " characters.";
        }

        try {
            Files.createDirectories(memoryPath.getParent());
            Files.writeString(memoryPath, fileContent, StandardCharsets.UTF_8);
            updateMemoryIndex(indexPath, normalizedName, normalizedType, savedAt, normalizedOpportunity, normalizedDescription);
            return "Saved memory " + normalizedName + "[" + normalizedType + "][" + normalizedOpportunity + "] to "
                    + root.relativize(memoryPath).toString().replace('\\', '/')
                    + " and updated MEMORY.md.";
        } catch (IOException e) {
            return "Failed to save memory: " + e.getMessage();
        }
    }

    public String readMemory(String workingDirectory, String name, String type) {
        String normalizedType = normalizeType(type);
        String normalizedName = normalizeName(name);
        Path root = resolveGlobalMemoryRoot();
        Path memoryPath = memoryPath(root, normalizedType, normalizedName);
        ensureInsideRoot(root, memoryPath);
        if (!Files.isRegularFile(memoryPath)) {
            return "Memory not found: " + normalizedName + "[" + normalizedType + "].";
        }
        try {
            return Files.readString(memoryPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Failed to read memory: " + e.getMessage();
        }
    }

    public String forgetMemory(String workingDirectory, String name, String type) {
        String normalizedType = normalizeType(type);
        String normalizedName = normalizeName(name);
        Path root = resolveGlobalMemoryRoot();
        Path memoryPath = memoryPath(root, normalizedType, normalizedName);
        Path indexPath = root.resolve(MEMORY_INDEX_FILE_NAME).normalize();
        ensureInsideRoot(root, memoryPath);
        ensureInsideRoot(root, indexPath);

        boolean deletedFile = false;
        try {
            if (Files.isRegularFile(memoryPath)) {
                Files.delete(memoryPath);
                deletedFile = true;
            }
            int removedIndexEntries = removeMemoryIndexEntry(indexPath, normalizedName, normalizedType);
            if (!deletedFile && removedIndexEntries == 0) {
                return "Memory not found: " + normalizedName + "[" + normalizedType + "].";
            }
            return "Forgot memory " + normalizedName + "[" + normalizedType + "]. Deleted file="
                    + deletedFile + ", removed index entries=" + removedIndexEntries + ".";
        } catch (IOException e) {
            return "Failed to forget memory: " + e.getMessage();
        }
    }

    private List<MemoryFile> listStoredMemoryFiles(Path root) {
        Path memoryRoot = root.resolve(MEMORY_DIRECTORY_NAME).normalize();
        ensureInsideRoot(root, memoryRoot);
        if (!Files.isDirectory(memoryRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(memoryRoot, 2)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .map(path -> {
                        String relativePath = root.relativize(path).toString().replace('\\', '/');
                        String id = "memory-file:" + relativePath;
                        return readMemoryFile(root, id, relativePath, relativePath);
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String readMemoryIndexForPrompt(Path indexPath) throws IOException {
        StringBuilder builder = new StringBuilder();
        int lineCount = 0;
        for (String line : Files.readAllLines(indexPath, StandardCharsets.UTF_8)) {
            if (lineCount >= MAX_INDEX_LINES) {
                break;
            }
            int additionalLength = line.length() + System.lineSeparator().length();
            if (builder.length() + additionalLength > MAX_INDEX_CHARS) {
                break;
            }
            builder.append(line).append(System.lineSeparator());
            lineCount++;
        }
        return builder.toString().strip();
    }

    private void updateMemoryIndex(Path indexPath, String name, String type, String savedAt, String opportunity,
                                   String description) throws IOException {
        String targetPrefix = "- " + name + "[" + type + "]";
        List<String> lines = Files.isRegularFile(indexPath)
                ? new ArrayList<>(Files.readAllLines(indexPath, StandardCharsets.UTF_8))
                : new ArrayList<>();
        lines.removeIf(line -> line.strip().startsWith(targetPrefix));
        if (lines.isEmpty()) {
            lines.add("# Memory Index");
            lines.add("");
        } else if (lines.stream().noneMatch(line -> line.strip().equals("# Memory Index"))) {
            if (!lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("# Memory Index");
            lines.add("");
        }
        if (!lines.get(lines.size() - 1).isBlank()) {
            lines.add("");
        }
        lines.add("- " + name + "[" + type + "][saved_at: " + savedAt + "][" + opportunity + "]: " + description);

        Path parent = indexPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(indexPath, String.join(System.lineSeparator(), lines).stripTrailing()
                + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private int removeMemoryIndexEntry(Path indexPath, String name, String type) throws IOException {
        if (!Files.isRegularFile(indexPath)) {
            return 0;
        }
        String targetPrefix = "- " + name + "[" + type + "]";
        List<String> lines = new ArrayList<>(Files.readAllLines(indexPath, StandardCharsets.UTF_8));
        int before = lines.size();
        lines.removeIf(line -> line.strip().startsWith(targetPrefix));
        int removed = before - lines.size();
        if (removed > 0) {
            Files.writeString(indexPath, String.join(System.lineSeparator(), lines).stripTrailing()
                    + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        return removed;
    }

    private Path memoryPath(Path root, String type, String name) {
        return root.resolve(MEMORY_DIRECTORY_NAME).resolve(type).resolve(name + ".md").normalize();
    }

    private Path rootForMemoryFile(String workingDirectory, String fileId) {
        if ("project".equals(fileId) || "local".equals(fileId) || "agent".equals(fileId)) {
            return resolveWorkingDirectory(workingDirectory);
        }
        return resolveGlobalMemoryRoot();
    }

    private MemoryFile readMemoryFile(Path root, String id, String label, String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        ensureInsideRoot(root, target);
        try {
            boolean exists = Files.isRegularFile(target);
            String content = exists ? Files.readString(target, StandardCharsets.UTF_8) : "";
            return new MemoryFile(id, label, relativePath, content, exists);
        } catch (IOException e) {
            return new MemoryFile(id, label, relativePath, "Read failed: " + e.getMessage(), false);
        }
    }

    private Path resolveWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            throw new IllegalArgumentException("Working directory is required.");
        }
        Path configured = Path.of(workingDirectory);
        Path resolved = configured.isAbsolute()
                ? configured.normalize()
                : applicationRoot.resolve(configured).normalize();
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Working directory does not exist or is not a directory: " + workingDirectory);
        }
        return resolved;
    }

    private Path resolveGlobalMemoryRoot() {
        if (!Files.isDirectory(applicationRoot)) {
            throw new IllegalArgumentException("Application directory does not exist or is not a directory: " + applicationRoot);
        }
        return applicationRoot;
    }

    private void ensureInsideRoot(Path root, Path target) {
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Memory file must be inside its root directory.");
        }
    }

    private String relativePathFor(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("Memory file id is required.");
        }
        return switch (fileId) {
            case "project" -> "AGENT.md";
            case "local" -> "AGENT.local.md";
            case "agent" -> ".agent/AGENT.md";
            case "memory-index" -> MEMORY_INDEX_FILE_NAME;
            default -> {
                if (fileId.startsWith("memory-file:")) {
                    String relativePath = fileId.substring("memory-file:".length()).replace('\\', '/');
                    if (!relativePath.startsWith(MEMORY_DIRECTORY_NAME + "/") || relativePath.contains("..")) {
                        throw new IllegalArgumentException("Unsupported memory file path: " + fileId);
                    }
                    yield relativePath;
                }
                throw new IllegalArgumentException("Unsupported memory file id: " + fileId);
            }
        };
    }

    private String labelFor(String fileId) {
        if (fileId == null) {
            return "Memory file";
        }
        return switch (fileId) {
            case "project" -> "Project rules";
            case "local" -> "Local rules";
            case "agent" -> "Agent rules";
            case "memory-index" -> "Memory index";
            default -> fileId.startsWith("memory-file:")
                    ? fileId.substring("memory-file:".length()).replace('\\', '/')
                    : "Memory file";
        };
    }

    private String normalizeType(String type) {
        String value = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
        if (!MEMORY_TYPES.contains(value)) {
            throw new IllegalArgumentException("Unsupported memory type: " + type
                    + ". Supported types: " + String.join(", ", MEMORY_TYPES));
        }
        return value;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Memory name is required.");
        }
        String value = name.strip().replaceAll("\\s+", "_");
        if (!SAFE_MEMORY_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Memory name must match [A-Za-z0-9][A-Za-z0-9_-]{0,79}.");
        }
        return value;
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Memory description is required.");
        }
        return description.strip().replaceAll("\\R+", " ");
    }

    private String normalizeOpportunity(String opportunity) {
        if (opportunity == null || opportunity.isBlank()) {
            throw new IllegalArgumentException("Memory opportunity is required.");
        }
        return opportunity.strip().replaceAll("\\R+", " ");
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Memory content is required.");
        }
        return content.strip();
    }

    private String nowAsMinute() {
        return LocalDateTime.now().format(MEMORY_TIME_FORMATTER);
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars).stripTrailing() + "\n...content truncated";
    }

    public record MemoryFile(String id, String label, String relativePath, String content, boolean exists) {
    }
}
