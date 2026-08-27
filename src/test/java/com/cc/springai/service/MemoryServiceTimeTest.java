package com.cc.springai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MemoryServiceTimeTest {
    Path tempDir;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("memory-service-test-");
        memoryService = new MemoryService(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void saveMemoryWritesSavedAtIntoIndexAndMemoryFile() throws Exception {
        String result = memoryService.saveMemory(
                tempDir.toString(),
                "prefer_tabs",
                "Prefer tabs",
                "user",
                "每次会话",
                "The user prefers tabs for indentation."
        );

        String index = Files.readString(tempDir.resolve("MEMORY.md"));
        String memory = Files.readString(tempDir.resolve("memory/user/prefer_tabs.md"));

        Pattern pattern = Pattern.compile("saved_at: (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})");
        Matcher indexMatcher = pattern.matcher(index);
        Matcher memoryMatcher = pattern.matcher(memory);

        assertThat(result).contains("Saved memory prefer_tabs[user][每次会话]");
        assertThat(indexMatcher.find()).isTrue();
        assertThat(memoryMatcher.find()).isTrue();
        assertThat(indexMatcher.group(1)).isEqualTo(memoryMatcher.group(1));
        assertThat(index).contains("- prefer_tabs[user][saved_at: " + indexMatcher.group(1) + "][每次会话]: Prefer tabs");
    }

    @Test
    void saveMemoryKeepsSavedAtAtMinutePrecision() {
        memoryService.saveMemory(
                tempDir.toString(),
                "prefer_chinese",
                "Prefer Chinese",
                "user",
                "每次会话",
                "The user prefers Chinese replies."
        );

        String memory = assertDoesNotThrow(() -> Files.readString(tempDir.resolve("memory/user/prefer_chinese.md")));
        assertThat(memory).containsPattern("saved_at: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    @Test
    void saveMemoryPreservesMemoryIndexFormatSectionAboveIndexSection() throws Exception {
        Files.writeString(tempDir.resolve("MEMORY.md"), """
                # Memory Index Format
                Use exactly one line per memory entry.

                # Memory Index
                - existing[user][saved_at: 2026-06-13 12:00][session start]: Existing memory
                """);

        memoryService.saveMemory(
                tempDir.toString(),
                "prefer_tabs",
                "Prefer tabs",
                "user",
                "每次会话开始时",
                "The user prefers tabs for indentation."
        );

        String index = Files.readString(tempDir.resolve("MEMORY.md"));
        String[] lines = index.split("\\R");
        assertThat(lines[0]).isEqualTo("# Memory Index Format");
        int formatLine = -1;
        int memoryLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals("# Memory Index Format")) {
                formatLine = i;
            }
            if (lines[i].equals("# Memory Index")) {
                memoryLine = i;
            }
        }
        assertThat(formatLine).isGreaterThanOrEqualTo(0);
        assertThat(memoryLine).isGreaterThan(formatLine);
        assertThat(index).contains("- prefer_tabs[user][saved_at:");
    }
}
