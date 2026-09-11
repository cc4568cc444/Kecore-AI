package com.cc.springai.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRerankerProcessManagerTest {

    @Test
    void buildsCommandFromEndpointAndConfiguredGpuSettings() {
        LocalRerankerProcessManager.Properties properties =
                new LocalRerankerProcessManager.Properties();
        properties.setPythonCommand("E:/python/python.exe");
        properties.setDevice("cuda:0");
        properties.setMaxLength(768);
        properties.setBatchSize(1);
        properties.setInstruction("financial evidence");

        LocalRerankerProcessManager manager = new LocalRerankerProcessManager(
                properties, "http://127.0.0.1:9010/");

        List<String> command = manager.buildCommand(Path.of("reranker.py"));

        assertThat(command).containsSubsequence(
                "--host", "127.0.0.1",
                "--port", "9010",
                "--device", "cuda:0",
                "--max-length", "768",
                "--batch-size", "1",
                "--instruction", "financial evidence");
    }
}
