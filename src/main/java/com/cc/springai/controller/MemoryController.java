package com.cc.springai.controller;

import com.cc.springai.service.MemoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public List<MemoryService.MemoryFile> listMemoryFiles(@RequestParam String workingDirectory) {
        return memoryService.listMemoryFiles(workingDirectory);
    }

    @PutMapping
    public MemoryService.MemoryFile saveMemoryFile(@RequestBody MemoryUpdateRequest request) {
        return memoryService.writeMemoryFile(request.workingDirectory(), request.fileId(), request.content());
    }

    @PostMapping
    public MemorySaveResponse saveMemory(@RequestBody MemoryCreateRequest request) {
        String message = memoryService.saveMemory(
                request.workingDirectory(),
                request.name(),
                request.description(),
                request.type(),
                request.opportunity(),
                request.content()
        );
        return new MemorySaveResponse(message);
    }

    public record MemoryUpdateRequest(String workingDirectory, String fileId, String content) {
    }

    public record MemoryCreateRequest(String workingDirectory, String name, String description, String type,
                                      String opportunity, String content) {
    }

    public record MemorySaveResponse(String message) {
    }
}
