package com.cc.springai.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileOpenControllerTest {

    @Test
    void invalidPathReturnsFailureResponseInsteadOfThrowing() {
        FileOpenController controller = new FileOpenController();

        FileOpenController.FileOpenResponse response = controller.openFile(
                new FileOpenController.FileOpenRequest("C:\\Users\\41760\\Desktop\\missing-file.zip")
        );

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("不存在");
    }

    @Test
    void resolveFileAcceptsDirectoryPaths() throws Exception {
        FileOpenController controller = new FileOpenController();
        Path tempDirectory = Files.createTempDirectory("file-open-controller");
        try {
            Path resolved = ReflectionTestUtils.invokeMethod(controller, "resolveFile", tempDirectory.toString());
            assertThat(resolved).isEqualTo(tempDirectory.normalize());
        } finally {
            Files.deleteIfExists(tempDirectory);
        }
    }

    @Test
    void windowsOpenCommandUsesExplorerForDirectoriesAndDefaultApplicationForFiles() throws Exception {
        FileOpenController controller = new FileOpenController();
        Path tempDirectory = Files.createTempDirectory("file-open-controller-dir");
        Path tempFile = Files.createTempFile("file-open-controller-file", ".txt");
        try {
            @SuppressWarnings("unchecked")
            List<String> directoryCommand = ReflectionTestUtils.invokeMethod(controller, "buildWindowsOpenCommand", tempDirectory);
            @SuppressWarnings("unchecked")
            List<String> fileCommand = ReflectionTestUtils.invokeMethod(controller, "buildWindowsOpenCommand", tempFile);

            assertThat(directoryCommand).containsExactly("explorer.exe", tempDirectory.toString());
            assertThat(fileCommand).containsExactly("cmd.exe", "/c", "start", "", tempFile.toString());
        } finally {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDirectory);
        }
    }
}
