package com.cc.springai.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import com.cc.springai.agent.AgentSandboxContext;
import com.cc.springai.config.ShellToolConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

@Component
public class BasicTools {

    private static final Logger log = LoggerFactory.getLogger(BasicTools.class);
    public static final String WORKING_DIRECTORY_CONTEXT_KEY = "workingDirectory";

    private static final int MAX_TEXT_LENGTH = 100_000;
    private static final int MAX_OUTPUT_BYTES = 20_000;
    private static final int MAX_GREP_FILES = 1_000;
    private static final Charset GBK = Charset.forName("GBK");

    private final Path applicationRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private final Duration commandTimeout;

    public BasicTools(ShellToolConfiguration.ShellToolProperties properties) {
        this.commandTimeout = properties == null || properties.getCommandTimeout() == null
                ? Duration.ofMinutes(15)
                : properties.getCommandTimeout();
    }

    @Tool(description = "Execute a shell command in the selected working directory by default. This changes the real environment and requires user approval before execution. Prefer readFile, writeFile, replaceText, copyFile, listFiles, or grep for file operations, especially when paths contain spaces or non-ASCII characters.")
    public String executeShellCommand(
            @ToolParam(description = "Shell command to execute, for example Get-ChildItem or mvn test. On Windows, cmd /c commands run through cmd.exe; other commands run through PowerShell.") String command,
            ToolContext toolContext) {
        if (command == null || command.isBlank()) {
            return "Command cannot be empty.";
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        long startedAtMs = System.currentTimeMillis();
        try {
            Path workingDirectory = resolveWorkingDirectory(toolContext);
            AgentSandboxContext sandbox = sandboxContext(toolContext);
            if (!sandbox.commandLooksWorkspaceScoped(command)) {
                return "Sandbox blocked shell command because it may access outside the workspace or require network/package access. Ask for approval or switch to Auto mode.";
            }
            log.info("Starting shell command: cwd={}, timeout={}s, command={}",
                    workingDirectory, Math.max(1L, commandTimeout.getSeconds()), command);
            ProcessBuilder processBuilder;
            if (isWindows()) {
                processBuilder = windowsShellProcessBuilder(command);
            } else {
                processBuilder = new ProcessBuilder("sh", "-lc", command);
            }

            processBuilder.directory(workingDirectory.toFile());
            processBuilder.redirectErrorStream(true);
            configureNonInteractiveEnvironment(processBuilder);
            Process process = processBuilder.start();
            closeChildInput(process);
            Future<String> output = executor.submit(() -> readLimitedOutput(process.getInputStream()));
            long timeoutSeconds = Math.max(1L, commandTimeout.getSeconds());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("Shell command timed out after {} ms: command={}", elapsedMs(startedAtMs), command);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return "Command timed out and was terminated.\n" + output.get(2, TimeUnit.SECONDS);
            }
            log.info("Finished shell command after {} ms: exitCode={}, command={}",
                    elapsedMs(startedAtMs), process.exitValue(), command);
            return "Exit code: " + process.exitValue() + "\n" + output.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Shell command failed after {} ms: command={}, error={}",
                    elapsedMs(startedAtMs), command, e.toString());
            return "Command execution failed: " + e.getMessage();
        } finally {
            executor.shutdownNow();
        }
    }

    private void configureNonInteractiveEnvironment(ProcessBuilder processBuilder) {
        processBuilder.environment().putIfAbsent("CI", "true");
        processBuilder.environment().putIfAbsent("NO_COLOR", "1");
        processBuilder.environment().putIfAbsent("npm_config_yes", "true");
        processBuilder.environment().putIfAbsent("npm_config_fund", "false");
    }

    private void closeChildInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
    }

    private long elapsedMs(long startedAtMs) {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private String windowsPowerShellBootstrap() {
        return """
                $OutputEncoding=[Console]::OutputEncoding=[Text.Encoding]::UTF8;
                [Console]::InputEncoding=[Text.Encoding]::UTF8;
                $processPath=[Environment]::GetEnvironmentVariable('Path','Process');
                $machinePath=[Environment]::GetEnvironmentVariable('Path','Machine');
                $userPath=[Environment]::GetEnvironmentVariable('Path','User');
                $env:Path=($processPath,$machinePath,$userPath | Where-Object { $_ }) -join ';';
                $ProgressPreference='SilentlyContinue';
                $VerbosePreference='SilentlyContinue';
                $InformationPreference='SilentlyContinue';
                """.replaceAll("\\R+", " ");
    }

    private ProcessBuilder windowsShellProcessBuilder(String command) {
        String trimmed = command == null ? "" : command.stripLeading();
        if (looksLikeCmdCommand(trimmed)) {
            return new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command);
        }
        String encodedCommand = Base64.getEncoder().encodeToString(
                (windowsPowerShellBootstrap() + command).getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-EncodedCommand",
                encodedCommand);
    }

    private boolean looksLikeCmdCommand(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        return lower.startsWith("cmd ")
                || lower.startsWith("cmd.exe ")
                || lower.matches("^[\"']?[^\"']+\\.(cmd|bat)[\"']?(\\s.*)?$");
    }

    @Tool(description = "Read a UTF-8 text file. Relative paths are resolved against the selected working directory; absolute paths are also supported.")
    public String readFile(
            @ToolParam(description = "File path; relative paths are resolved against the current working directory") String path,
            ToolContext toolContext) {
        try {
            Path target = resolveWorkspacePath(path, toolContext);
            if (!Files.isRegularFile(target)) {
                return "File does not exist or is not a regular file: " + path;
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            if (content.length() > MAX_TEXT_LENGTH) {
                return content.substring(0, MAX_TEXT_LENGTH) + "\n...content truncated";
            }
            return content;
        } catch (Exception e) {
            return "Read file failed: " + e.getMessage();
        }
    }

    @Tool(description = "List files and subdirectories in a directory. Relative paths are resolved against the selected working directory; pass . for the current workspace.")
    public String listFiles(
            @ToolParam(description = "Directory path, for example . or src/main/java") String path,
            ToolContext toolContext) {
        try {
            Path directory = resolveWorkspacePath(path == null || path.isBlank() ? "." : path, toolContext);
            if (!Files.isDirectory(directory)) {
                return "Directory does not exist or is not a directory: " + path;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                String listing = entries
                        .sorted(Comparator.comparing(item -> item.getFileName().toString().toLowerCase()))
                        .limit(100)
                        .map(item -> {
                            try {
                                if (Files.isDirectory(item)) {
                                    return "[DIR] " + item.getFileName();
                                }
                                return "[FILE] " + item.getFileName() + " (" + Files.size(item) + " bytes)";
                            } catch (IOException e) {
                                return "[FILE] " + item.getFileName();
                            }
                        })
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("(empty directory)");
                return "Directory: " + directory + "\n" + listing;
            }
        } catch (Exception e) {
            return "List files failed: " + e.getMessage();
        }
    }

    @Tool(description = "Search UTF-8 text files with a regular expression, similar to grep. Relative paths are resolved against the selected working directory; pass . to search the current workspace recursively.")
    public String grep(
            @ToolParam(description = "Search root path; relative paths are resolved against the current working directory") String path,
            @ToolParam(description = "Regular expression pattern to search for") String pattern,
            @ToolParam(description = "Optional filename glob such as *.java, *.md, or **/*.java; defaults to all files", required = false) String fileGlob,
            @ToolParam(description = "Whether the pattern match is case sensitive; defaults to true", required = false) Boolean caseSensitive,
            @ToolParam(description = "Maximum number of matches to return; defaults to 100", required = false) Integer maxMatches,
            ToolContext toolContext) {
        if (pattern == null || pattern.isBlank()) {
            return "Pattern cannot be empty.";
        }

        Pattern compiledPattern;
        try {
            int flags = Boolean.FALSE.equals(caseSensitive)
                    ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                    : 0;
            compiledPattern = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return "Invalid regex pattern: " + e.getMessage();
        }

        int limit = maxMatches == null ? 100 : Math.max(1, Math.min(maxMatches, 500));
        String glob = fileGlob == null || fileGlob.isBlank() ? "*" : fileGlob.trim();
        List<String> matches = new ArrayList<>();
        int scannedFiles = 0;

        try {
            Path root = resolveWorkspacePath(path == null || path.isBlank() ? "." : path, toolContext);
            List<Path> files = collectSearchFiles(root, glob);
            for (Path file : files) {
                if (matches.size() >= limit) {
                    break;
                }
                scannedFiles++;
                matches.addAll(searchFile(file, compiledPattern, limit - matches.size(), toolContext));
            }
            if (matches.isEmpty()) {
                return "No matches found.\nscanned_files: " + scannedFiles + "\nroot: " + displayPath(root, toolContext);
            }
            return """
                    root: %s
                    scanned_files: %d
                    matched_lines: %d
                    %s
                    """.formatted(
                    displayPath(root, toolContext),
                    scannedFiles,
                    matches.size(),
                    String.join("\n", matches));
        } catch (Exception e) {
            return "grep failed: " + e.getMessage();
        }
    }

    @Tool(description = "Open a file with the operating system default application. Relative paths are resolved against the selected working directory. Call this only after the user explicitly asks to open the target file.")
    public String openFile(
            @ToolParam(description = "File path to open; relative paths are resolved against the current working directory") String path,
            ToolContext toolContext) {
        try {
            Path target = resolveWorkspacePath(path, toolContext);
            if (!Files.isRegularFile(target)) {
                return "File does not exist or is not a regular file: " + path;
            }
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                return "The current runtime does not support opening files with the OS default application.";
            }
            Desktop.getDesktop().open(target.toFile());
            return "Requested OS default application to open file: " + displayPath(target, toolContext);
        } catch (Exception e) {
            return "Open file failed: " + e.getMessage();
        }
    }

    @Tool(description = "Write UTF-8 text to a file, replacing existing content. Relative paths are resolved against the selected working directory. This requires user approval before execution.")
    public String writeFile(
            @ToolParam(description = "Target file path; relative paths are resolved against the current working directory") String path,
            @ToolParam(description = "Complete text content to write") String content,
            ToolContext toolContext) {
        if (content != null && content.length() > MAX_TEXT_LENGTH) {
            return "Content is too long. Maximum allowed characters: " + MAX_TEXT_LENGTH;
        }
        try {
            Path target = resolveWorkspacePath(path, toolContext);
            createParentDirectory(target);
            writeTextFile(target, content == null ? "" : content);
            return "Wrote file: " + displayPath(target, toolContext);
        } catch (Exception e) {
            return "Write file failed: " + e.getMessage();
        }
    }

    @Tool(description = "Replace exact text in a UTF-8 file. Relative paths are resolved against the selected working directory. This requires user approval before execution.")
    public String replaceText(
            @ToolParam(description = "File path; relative paths are resolved against the current working directory") String path,
            @ToolParam(description = "Exact text to replace") String oldText,
            @ToolParam(description = "Replacement text") String newText,
            ToolContext toolContext) {
        if (oldText == null || oldText.isEmpty()) {
            return "Old text cannot be empty.";
        }
        try {
            Path target = resolveWorkspacePath(path, toolContext);
            String content = Files.readString(target, StandardCharsets.UTF_8);
            int replacements = countOccurrences(content, oldText);
            if (replacements == 0) {
                return "Text to replace was not found; file was not modified.";
            }
            String updated = content.replace(oldText, newText == null ? "" : newText);
            if (updated.length() > MAX_TEXT_LENGTH) {
                return "Updated file content is too long; write was not performed.";
            }
            writeTextFile(target, updated);
            return "Text replacement completed. Replacement count: " + replacements;
        } catch (Exception e) {
            return "Replace text failed: " + e.getMessage();
        }
    }

    @Tool(description = "Copy a file. Relative paths are resolved against the selected working directory. This requires user approval before execution. Prefer this tool over shell copy commands when paths contain spaces or non-ASCII characters.")
    public String copyFile(
            @ToolParam(description = "Source file path; relative paths are resolved against the current working directory") String sourcePath,
            @ToolParam(description = "Target file path; relative paths are resolved against the current working directory") String targetPath,
            @ToolParam(description = "Whether to overwrite the target file if it exists") boolean overwrite,
            ToolContext toolContext) {
        try {
            Path source = resolveWorkspacePath(sourcePath, toolContext);
            Path target = resolveWorkspacePath(targetPath, toolContext);
            if (!Files.isRegularFile(source)) {
                return "Source file does not exist or is not a regular file: " + sourcePath;
            }
            createParentDirectory(target);
            CopyOption[] options = overwrite
                    ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                    : new CopyOption[0];
            Files.copy(source, target, options);
            return "Copied file to: " + displayPath(target, toolContext);
        } catch (Exception e) {
            return "Copy file failed: " + e.getMessage();
        }
    }

    private Path resolveWorkspacePath(String requestedPath, ToolContext toolContext) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new IllegalArgumentException("File path cannot be empty.");
        }
        AgentSandboxContext sandbox = sandboxContext(toolContext);
        if (sandbox.enabled()) {
            return sandbox.resolveWorkspacePath(requestedPath);
        }
        Path path = Path.of(requestedPath);
        return path.isAbsolute()
                ? path.normalize()
                : resolveWorkingDirectory(toolContext).resolve(path).normalize();
    }

    private Path resolveWorkingDirectory(ToolContext toolContext) {
        Object configuredDirectory = toolContext == null
                ? null
                : toolContext.getContext().get(WORKING_DIRECTORY_CONTEXT_KEY);
        if (!(configuredDirectory instanceof String directory) || directory.isBlank()) {
            throw new IllegalArgumentException("Please select a working directory in Agent mode first.");
        }
        Path selectedPath = Path.of(directory);
        Path resolvedPath = selectedPath.isAbsolute()
                ? selectedPath.normalize()
                : applicationRoot.resolve(selectedPath).normalize();
        if (!Files.isDirectory(resolvedPath)) {
            throw new IllegalArgumentException("Working directory does not exist or is not a directory: " + directory);
        }
        return resolvedPath;
    }

    private AgentSandboxContext sandboxContext(ToolContext toolContext) {
        Object configured = toolContext == null
                ? null
                : toolContext.getContext().get(AgentSandboxContext.TOOL_CONTEXT_KEY);
        if (configured instanceof AgentSandboxContext sandbox) {
            return sandbox;
        }
        return AgentSandboxContext.of(false, "");
    }

    private String displayPath(Path target, ToolContext toolContext) {
        Path workingDirectory = resolveWorkingDirectory(toolContext);
        return target.startsWith(workingDirectory)
                ? workingDirectory.relativize(target).toString()
                : target.toString();
    }

    private void createParentDirectory(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void writeTextFile(Path target, String content) throws IOException {
        if (isWindows() && target.getFileName() != null
                && target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ps1")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(0xEF);
            output.write(0xBB);
            output.write(0xBF);
            output.write(content.getBytes(StandardCharsets.UTF_8));
            Files.write(target, output.toByteArray());
            return;
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private List<Path> collectSearchFiles(Path root, String fileGlob) throws IOException {
        Path base = Files.isDirectory(root) ? root : root.getParent();
        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + fileGlob);
        try (Stream<Path> stream = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesGlob(base, path, matcher, fileGlob))
                    .limit(MAX_GREP_FILES)
                    .sorted()
                    .toList();
        }
    }

    private boolean matchesGlob(Path base, Path path, PathMatcher matcher, String fileGlob) {
        if (fileGlob == null || fileGlob.isBlank() || "*".equals(fileGlob.trim())) {
            return true;
        }
        Path fileName = path.getFileName();
        if (fileName != null && matcher.matches(fileName)) {
            return true;
        }
        if (base == null || !path.startsWith(base)) {
            return false;
        }
        return matcher.matches(base.relativize(path));
    }

    private List<String> searchFile(Path file, Pattern pattern, int remainingMatches, ToolContext toolContext) {
        List<String> results = new ArrayList<>();
        if (remainingMatches <= 0) {
            return results;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null && results.size() < remainingMatches) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    results.add("%s:%d:%s".formatted(displayPath(file, toolContext), lineNumber, clipLine(line)));
                }
                lineNumber++;
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private String clipLine(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.strip();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240) + "...";
    }

    private int countOccurrences(String content, String target) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    private String readLimitedOutput(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        boolean truncated = false;
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            int remaining = MAX_OUTPUT_BYTES - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(length, remaining));
            }
            if (length > remaining) {
                truncated = true;
            }
        }
        String value = sanitizeShellOutput(decodeProcessOutput(output.toByteArray()));
        return truncated ? value + "\n...output truncated" : value;
    }

    private String sanitizeShellOutput(String output) {
        if (output == null || output.isBlank()) {
            return output == null ? "" : output;
        }
        String normalized = output.replace("\r\n", "\n");
        int cliXmlIndex = normalized.indexOf("#< CLIXML");
        if (cliXmlIndex < 0) {
            return output;
        }
        String beforeCliXml = normalized.substring(0, cliXmlIndex).stripTrailing();
        String afterCliXml = stripCliXmlBlocks(normalized.substring(cliXmlIndex));
        if (afterCliXml.isBlank()) {
            return beforeCliXml;
        }
        if (beforeCliXml.isBlank()) {
            return afterCliXml.stripLeading();
        }
        return beforeCliXml + "\n" + afterCliXml.stripLeading();
    }

    private String stripCliXmlBlocks(String output) {
        String remaining = output;
        while (remaining.startsWith("#< CLIXML")) {
            int objsStart = remaining.indexOf("<Objs");
            int objsEnd = remaining.indexOf("</Objs>");
            if (objsStart < 0 || objsEnd < objsStart) {
                return "";
            }
            remaining = remaining.substring(objsEnd + "</Objs>".length()).stripLeading();
        }
        return remaining;
    }

    private String decodeProcessOutput(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        if (startsWith(bytes, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        Charset utf16ByPattern = likelyUtf16Charset(bytes);
        if (utf16ByPattern != null) {
            return new String(bytes, utf16ByPattern);
        }

        Set<Charset> candidates = new LinkedHashSet<>();
        candidates.add(StandardCharsets.UTF_8);
        candidates.add(Charset.defaultCharset());
        candidates.add(GBK);

        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Charset charset : candidates) {
            String decoded = decodeStrict(bytes, charset);
            if (decoded == null) {
                continue;
            }
            int score = readabilityScore(decoded);
            if (score > bestScore) {
                bestScore = score;
                best = decoded;
            }
        }
        return best != null ? best : new String(bytes, StandardCharsets.UTF_8);
    }

    private boolean startsWith(byte[] bytes, byte... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private Charset likelyUtf16Charset(byte[] bytes) {
        int evenNul = 0;
        int oddNul = 0;
        int sampled = Math.min(bytes.length, 200);
        for (int i = 0; i < sampled; i++) {
            if (bytes[i] == 0) {
                if (i % 2 == 0) {
                    evenNul++;
                } else {
                    oddNul++;
                }
            }
        }
        int pairs = Math.max(1, sampled / 2);
        if (oddNul > pairs / 3 && evenNul < Math.max(2, oddNul / 4)) {
            return StandardCharsets.UTF_16LE;
        }
        if (evenNul > pairs / 3 && oddNul < Math.max(2, evenNul / 4)) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    private String decodeStrict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private int readabilityScore(String text) {
        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFD' || ch == '\u0000') {
                score -= 80;
            } else if (ch >= '\u4E00' && ch <= '\u9FFF') {
                score += 3;
            } else if (ch == '\n' || ch == '\r' || ch == '\t') {
                score += 1;
            } else if (ch >= 32 && ch < 127) {
                score += 2;
            } else if (Character.isISOControl(ch)) {
                score -= 20;
            } else {
                score += 1;
            }
        }
        score -= mojibakePenalty(text);
        return score;
    }

    private int mojibakePenalty(String text) {
        int penalty = 0;
        String suspicious = "锟斤拷烫屯����";
        for (int i = 0; i < text.length(); i++) {
            if (suspicious.indexOf(text.charAt(i)) >= 0) {
                penalty += 12;
            }
        }
        penalty += countOccurrences(text, "鍛") * 20;
        penalty += countOccurrences(text, "姤") * 20;
        penalty += countOccurrences(text, "鐭") * 20;
        penalty += countOccurrences(text, "澶") * 12;
        penalty += countOccurrences(text, "瀹") * 12;
        return penalty;
    }
}
