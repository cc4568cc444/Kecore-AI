package com.cc.springai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts the local Python reranker together with Spring Boot when it is not already running.
 * A reranker that was started outside this application is deliberately left untouched on shutdown.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.financial-rag.rerank.auto-start",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(LocalRerankerProcessManager.Properties.class)
public class LocalRerankerProcessManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LocalRerankerProcessManager.class);

    private final Properties properties;
    private final URI healthUri;
    private final HttpClient httpClient;

    private volatile Process process;
    private volatile boolean running;
    private volatile boolean externallyManaged;

    public LocalRerankerProcessManager(
            Properties properties,
            @Value("${app.financial-rag.rerank.url:http://127.0.0.1:8010}") String rerankerUrl) {
        this.properties = properties;
        this.healthUri = URI.create(rerankerUrl.replaceAll("/+$", "") + "/health");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (isHealthy()) {
            externallyManaged = true;
            running = true;
            log.info("Reranker is already healthy at {}; using the existing process", healthUri);
            return;
        }

        try {
            Path script = resolveScriptPath();
            List<String> command = buildCommand(script);
            log.info("Reranker is not reachable at {}; starting {}", healthUri, script);
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(Path.of("").toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true);
            process = processBuilder.start();
            streamProcessOutput(process);
            waitUntilReady();
            running = true;
            log.info("Local reranker is ready at {} (pid={})", healthUri, process.pid());
        } catch (Exception exception) {
            stopOwnedProcess();
            String message = "Failed to start local reranker: " + exception.getMessage();
            if (properties.isRequired()) {
                throw new IllegalStateException(message, exception);
            }
            log.warn("{}. The application will use retrieval fallback until reranker is available.",
                    message, exception);
        }
    }

    Path resolveScriptPath() {
        Path configured = Path.of(properties.getScript()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(configured)) {
            throw new IllegalStateException("reranker script does not exist: " + configured);
        }
        return configured;
    }

    List<String> buildCommand(Path script) {
        List<String> command = new ArrayList<>(List.of(
                properties.getPythonCommand(),
                script.toString(),
                "--host", healthUri.getHost(),
                "--port", Integer.toString(effectivePort()),
                "--model-path", properties.getModelPath(),
                "--device", properties.getDevice(),
                "--max-length", Integer.toString(properties.getMaxLength()),
                "--batch-size", Integer.toString(properties.getBatchSize())
        ));
        if (properties.getInstruction() != null && !properties.getInstruction().isBlank()) {
            command.add("--instruction");
            command.add(properties.getInstruction());
        }
        return List.copyOf(command);
    }

    private int effectivePort() {
        if (healthUri.getPort() > 0) {
            return healthUri.getPort();
        }
        return "https".equalsIgnoreCase(healthUri.getScheme()) ? 443 : 80;
    }

    private void waitUntilReady() throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(properties.getStartupTimeout());
        while (Instant.now().isBefore(deadline)) {
            Process current = process;
            if (current == null || !current.isAlive()) {
                int exitCode = current == null ? -1 : current.exitValue();
                throw new IOException("reranker process exited before becoming ready (exit="
                        + exitCode + ")");
            }
            if (isHealthy()) {
                return;
            }
            Thread.sleep(properties.getPollInterval().toMillis());
        }
        throw new IOException("health check did not succeed within " + properties.getStartupTimeout());
    }

    private boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void streamProcessOutput(Process child) {
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[reranker] {}", line);
                }
            } catch (IOException exception) {
                if (child.isAlive()) {
                    log.warn("Unable to read reranker output", exception);
                }
            }
        }, "local-reranker-output");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    @Override
    public synchronized void stop() {
        if (!externallyManaged) {
            stopOwnedProcess();
        }
        externallyManaged = false;
        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    private void stopOwnedProcess() {
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) {
            return;
        }
        log.info("Stopping local reranker (pid={})", current.pid());
        current.destroy();
        try {
            if (!current.waitFor(10, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                current.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    @ConfigurationProperties(prefix = "app.financial-rag.rerank.auto-start")
    public static class Properties {
        private String pythonCommand = "python";
        private String script = "src/main/resources/reranker/qwen3_reranker_server.py";
        private String modelPath = "Qwen/Qwen3-Reranker-0.6B";
        private String device = "auto";
        private int maxLength = 1024;
        private int batchSize = 2;
        private Duration startupTimeout = Duration.ofMinutes(10);
        private Duration pollInterval = Duration.ofSeconds(1);
        private boolean required = true;
        private String instruction = "Given a financial filing question, rank passages by whether they contain the exact evidence, entities, periods, and figures needed to answer it.";

        public String getPythonCommand() { return pythonCommand; }
        public void setPythonCommand(String pythonCommand) { this.pythonCommand = pythonCommand; }
        public String getScript() { return script; }
        public void setScript(String script) { this.script = script; }
        public String getModelPath() { return modelPath; }
        public void setModelPath(String modelPath) { this.modelPath = modelPath; }
        public String getDevice() { return device; }
        public void setDevice(String device) { this.device = device; }
        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getStartupTimeout() { return startupTimeout; }
        public void setStartupTimeout(Duration startupTimeout) { this.startupTimeout = startupTimeout; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public String getInstruction() { return instruction; }
        public void setInstruction(String instruction) { this.instruction = instruction; }
    }
}
