package com.cc.springai.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/files")
public class FileOpenController {

    @PostMapping("/open")
    public FileOpenResponse openFile(@RequestBody FileOpenRequest request) {
        try {
            if (request == null) {
                return new FileOpenResponse(false, "文件路径不能为空。", null);
            }
            Path target = resolveFile(request.path());
            openWithDefaultApplication(target);
            return new FileOpenResponse(true, "已请求系统默认程序打开文件。", target.toString());
        } catch (IllegalArgumentException e) {
            return new FileOpenResponse(false, e.getMessage(), request == null ? null : request.path());
        } catch (Exception e) {
            return new FileOpenResponse(false, "打开文件失败：" + e.getMessage(),
                    request == null ? null : request.path());
        }
    }

    private Path resolveFile(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空。");
        }
        Path requested = Path.of(path);
        if (!requested.isAbsolute()) {
            throw new IllegalArgumentException("只支持打开绝对路径文件。");
        }
        Path target = requested.normalize();
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("文件或文件夹不存在：" + target);
        }
        if (!Files.isRegularFile(target) && !Files.isDirectory(target)) {
            throw new IllegalArgumentException("路径不是可打开的文件或文件夹：" + target);
        }
        return target;
    }

    private void openWithDefaultApplication(Path target) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            new ProcessBuilder(buildWindowsOpenCommand(target)).start();
            return;
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(target.toFile());
            return;
        }

        ProcessBuilder processBuilder;
        if (os.contains("mac")) {
            processBuilder = new ProcessBuilder("open", target.toString());
        } else {
            processBuilder = new ProcessBuilder("xdg-open", target.toString());
        }
        processBuilder.start();
    }

    List<String> buildWindowsOpenCommand(Path target) {
        if (Files.isDirectory(target)) {
            return List.of("explorer.exe", target.toString());
        }
        return List.of("cmd.exe", "/c", "start", "", target.toString());
    }

    public record FileOpenRequest(String path) {
    }

    public record FileOpenResponse(boolean success, String message, String path) {
    }
}
