package com.cc.springai.controller;

import com.cc.springai.service.RagService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
public class UploadController {
    private final RagService ragService;

    public UploadController(RagService ragService) {
        this.ragService = ragService;
    }
    
    @PostMapping({"/upload", "/rag/upload"})
    public RagService.UploadResult upload(@RequestParam("conversationId") String conversationId,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        return ragService.uploadPdf(conversationId, file);
    }

    @GetMapping("/rag/documents")
    public List<RagService.KnowledgeDocumentSummary> listDocuments(@RequestParam("conversationId") String conversationId) {
        return ragService.listDocuments(conversationId);
    }

    @DeleteMapping("/rag/documents/{documentId}")
    public void deleteDocument(@RequestParam("conversationId") String conversationId,
                               @PathVariable String documentId) {
        ragService.deleteDocument(conversationId, documentId);
    }
}
