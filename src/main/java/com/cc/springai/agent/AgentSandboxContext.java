package com.cc.springai.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public record AgentSandboxContext(boolean enabled, String workingDirectory) {

    public static final String TOOL_CONTEXT_KEY = "sandbox";

    public static AgentSandboxContext of(boolean enabled, String workingDirectory) {
        return new AgentSandboxContext(enabled, workingDirectory == null ? "" : workingDirectory);
    }

    public Path workspaceRoot() {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            throw new IllegalArgumentException("Please select a working directory in Agent mode first.");
        }
        Path root = Path.of(workingDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Working directory does not exist or is not a directory: " + workingDirectory);
        }
        return root;
    }

    public Path resolveWorkspacePath(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new IllegalArgumentException("Path cannot be empty.");
        }
        Path path = Path.of(requestedPath);
        Path resolved = path.isAbsolute()
                ? path.normalize()
                : workspaceRoot().resolve(path).normalize();
        ensureInsideWorkspace(resolved);
        return resolved;
    }

    public boolean containsPath(String requestedPath) {
        try {
            if (requestedPath == null || requestedPath.isBlank()) {
                return false;
            }
            Path path = Path.of(requestedPath);
            Path resolved = path.isAbsolute()
                    ? path.normalize()
                    : workspaceRoot().resolve(path).normalize();
            return resolved.toAbsolutePath().normalize().startsWith(workspaceRoot());
        } catch (Exception ignored) {
            return false;
        }
    }

    public void ensureInsideWorkspace(Path target) {
        if (enabled && !target.toAbsolutePath().normalize().startsWith(workspaceRoot())) {
            throw new SecurityException("Sandbox blocked access outside the workspace: " + target);
        }
    }

    public boolean commandLooksWorkspaceScoped(String command) {
        if (!enabled || command == null || command.isBlank()) {
            return true;
        }
        String text = command.toLowerCase(Locale.ROOT);
        return !(text.contains("..")
                || text.matches(".*\\b[a-z]:\\\\.*")
                || text.matches(".*\\s/[^\\s].*")
                || text.contains("set-location")
                || text.matches(".*\\bcd\\s+.*")
                || text.contains("invoke-webrequest")
                || text.contains("curl ")
                || text.contains("wget ")
                || text.contains("git clone")
                || text.contains("npm install")
                || text.contains("pnpm install")
                || text.contains("yarn install")
                || text.contains("mvn dependency:")
                || text.contains("mvn -u"));
    }

    public boolean commandLooksWorkspaceScopedForApproval(String command) {
        return AgentSandboxContext.of(true, workingDirectory).commandLooksWorkspaceScoped(command);
    }
}
