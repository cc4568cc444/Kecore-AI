package com.cc.springai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class RagService {

    private static final int PARENT_CHUNK_SIZE = 256;
    private static final int CHILD_CHUNK_SIZE = 64;
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 1;
    private static final String CHILD_CHUNK_TYPE = "child";
    private static final String PARENT_CHUNK_TYPE = "parent";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RagService(VectorStore vectorStore, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rag_documents (
                    conversation_id VARCHAR(128) NOT NULL,
                    document_id VARCHAR(128) NOT NULL,
                    file_name TEXT NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    chunk_ids TEXT NOT NULL,
                    created_at BIGINT NOT NULL,
                    PRIMARY KEY (conversation_id, document_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rag_parent_chunks (
                    conversation_id VARCHAR(128) NOT NULL,
                    document_id VARCHAR(128) NOT NULL,
                    parent_chunk_id VARCHAR(128) NOT NULL,
                    parent_index INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    metadata TEXT NOT NULL,
                    created_at BIGINT NOT NULL,
                    PRIMARY KEY (conversation_id, document_id, parent_chunk_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_rag_parent_chunks_lookup
                ON rag_parent_chunks (conversation_id, parent_chunk_id)
                """);
    }

    public UploadResult uploadPdf(String conversationId, MultipartFile file) throws IOException {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId不能为空");
        }

        String originalFilename = validatePdf(file);
        String documentId = UUID.randomUUID().toString();
        Path tempFile = Files.createTempFile("rag-", ".pdf");

        try {
            file.transferTo(tempFile.toFile());

            PagePdfDocumentReader reader = new PagePdfDocumentReader(new FileSystemResource(tempFile));
            List<Document> documents = reader.get().stream()
                    .map(document -> document.mutate()
                            .metadata("documentId", documentId)
                            .metadata("fileName", originalFilename)
                            .metadata("conversationId", conversationId)
                            .build())
                    .toList();

            ParentChildChunks chunks = splitParentChildChunks(documents);

            int batchSize = 10;
            for (int i = 0; i < chunks.childChunks().size(); i += batchSize) {
                int end = Math.min(i + batchSize, chunks.childChunks().size());
                vectorStore.add(chunks.childChunks().subList(i, end));
            }
            rememberParentChunks(conversationId, documentId, chunks.parentChunks());
            rememberDocument(conversationId, documentId, originalFilename, chunks.childChunks());

            return new UploadResult(documentId, originalFilename, documents.size(), chunks.childChunks().size());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private ParentChildChunks splitParentChildChunks(List<Document> documents) {
        TokenTextSplitter parentSplitter = TokenTextSplitter.builder()
                .withChunkSize(PARENT_CHUNK_SIZE)
                .withMinChunkLengthToEmbed(MIN_CHUNK_LENGTH_TO_EMBED)
                .build();
        TokenTextSplitter childSplitter = TokenTextSplitter.builder()
                .withChunkSize(CHILD_CHUNK_SIZE)
                .withMinChunkLengthToEmbed(MIN_CHUNK_LENGTH_TO_EMBED)
                .build();

        List<Document> parentDocuments = parentSplitter.split(documents);
        List<ParentChunk> parentChunks = new ArrayList<>();
        List<Document> childChunks = new ArrayList<>();

        for (int parentIndex = 0; parentIndex < parentDocuments.size(); parentIndex++) {
            Document parentDocument = parentDocuments.get(parentIndex);
            String parentChunkId = parentDocument.getId();
            Map<String, Object> parentMetadata = new LinkedHashMap<>(parentDocument.getMetadata());
            parentMetadata.put("chunkType", PARENT_CHUNK_TYPE);
            parentMetadata.put("parentChunkId", parentChunkId);
            parentMetadata.put("parentChunkIndex", parentIndex);
            parentChunks.add(new ParentChunk(parentChunkId, parentIndex, parentDocument.getText(), parentMetadata));

            List<Document> splitChildren = childSplitter.split(List.of(parentDocument.mutate()
                    .metadata(parentMetadata)
                    .build()));
            for (int childIndex = 0; childIndex < splitChildren.size(); childIndex++) {
                Document childDocument = splitChildren.get(childIndex);
                childChunks.add(childDocument.mutate()
                        .metadata("chunkType", CHILD_CHUNK_TYPE)
                        .metadata("parentChunkId", parentChunkId)
                        .metadata("parentChunkIndex", parentIndex)
                        .metadata("childChunkIndex", childIndex)
                        .build());
            }
        }

        return new ParentChildChunks(parentChunks, childChunks);
    }

    private String validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        if (!originalFilename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("只支持上传 PDF 文件");
        }

        return originalFilename;
    }

    private void rememberDocument(String conversationId, String documentId, String fileName,
                                  List<Document> splitDocuments) {
        List<String> chunkIds = splitDocuments.stream()
                .map(Document::getId)
                .toList();
        jdbcTemplate.update("""
                INSERT INTO rag_documents (conversation_id, document_id, file_name, chunk_count, chunk_ids, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id, document_id) DO UPDATE SET
                    file_name = EXCLUDED.file_name,
                    chunk_count = EXCLUDED.chunk_count,
                    chunk_ids = EXCLUDED.chunk_ids,
                    created_at = EXCLUDED.created_at
                """, conversationId, documentId, fileName, chunkIds.size(), writeChunkIds(chunkIds), System.currentTimeMillis());
    }

    private void rememberParentChunks(String conversationId, String documentId, List<ParentChunk> parentChunks) {
        long createdAt = System.currentTimeMillis();
        for (ParentChunk parentChunk : parentChunks) {
            jdbcTemplate.update("""
                    INSERT INTO rag_parent_chunks
                        (conversation_id, document_id, parent_chunk_id, parent_index, text, metadata, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (conversation_id, document_id, parent_chunk_id) DO UPDATE SET
                        parent_index = EXCLUDED.parent_index,
                        text = EXCLUDED.text,
                        metadata = EXCLUDED.metadata,
                        created_at = EXCLUDED.created_at
                    """,
                    conversationId,
                    documentId,
                    parentChunk.id(),
                    parentChunk.index(),
                    parentChunk.text(),
                    writeMetadata(parentChunk.metadata()),
                    createdAt);
        }
    }

    public List<KnowledgeDocumentSummary> listDocuments(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId不能为空");
        }

        return jdbcTemplate.query("""
                        SELECT document_id, file_name, chunk_count
                        FROM rag_documents
                        WHERE conversation_id = ?
                        ORDER BY created_at DESC
                        """,
                (rs, rowNum) -> new KnowledgeDocumentSummary(
                        rs.getString("document_id"),
                        rs.getString("file_name"),
                        rs.getInt("chunk_count")
                ),
                conversationId);
    }

    public void deleteDocument(String conversationId, String documentId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId不能为空");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId不能为空");
        }

        List<String> chunkIds = jdbcTemplate.query("""
                        SELECT chunk_ids
                        FROM rag_documents
                        WHERE conversation_id = ? AND document_id = ?
                        """,
                (rs, rowNum) -> readChunkIds(rs.getString("chunk_ids")),
                conversationId,
                documentId)
                .stream()
                .findFirst()
                .orElse(null);

        if (chunkIds == null) {
            throw new IllegalArgumentException("当前会话没有该知识库文档");
        }

        if (!chunkIds.isEmpty()) {
            vectorStore.delete(chunkIds);
        }
        jdbcTemplate.update("DELETE FROM rag_parent_chunks WHERE conversation_id = ? AND document_id = ?",
                conversationId, documentId);
        jdbcTemplate.update("DELETE FROM rag_documents WHERE conversation_id = ? AND document_id = ?",
                conversationId, documentId);
    }

    public void deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        List<List<String>> chunkIdGroups = jdbcTemplate.query(
                "SELECT chunk_ids FROM rag_documents WHERE conversation_id = ?",
                (rs, rowNum) -> readChunkIds(rs.getString("chunk_ids")),
                conversationId);
        List<String> chunkIds = chunkIdGroups.stream()
                .flatMap(List::stream)
                .toList();
        if (!chunkIds.isEmpty()) {
            vectorStore.delete(chunkIds);
        }
        jdbcTemplate.update("DELETE FROM rag_parent_chunks WHERE conversation_id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM rag_documents WHERE conversation_id = ?", conversationId);
    }

    public List<RagChunk> search(String query, String conversationId, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId不能为空");
        }

        List<Document> res = searchChildChunks(query, conversationId, topK);
        if (res.isEmpty()) {
            res = searchLegacyChunks(query, conversationId, topK);
        }

        List<RagChunk> ans = new ArrayList<>();
        Map<String, RagChunk> parentChunks = new LinkedHashMap<>();
        for (Document d : res) {
            String parentChunkId = asString(d.getMetadata().get("parentChunkId"));
            if (parentChunkId == null || parentChunkId.isBlank()) {
                ans.add(new RagChunk(d.getText(), d.getScore(), d.getMetadata()));
                continue;
            }
            if (!parentChunks.containsKey(parentChunkId)) {
                parentChunks.put(parentChunkId, loadParentChunk(conversationId, parentChunkId, d));
            }
        }
        ans.addAll(parentChunks.values());
        return ans;
    }

    private List<Document> searchChildChunks(String query, String conversationId, int topK) {
        int actualTopK = topK <= 0 ? 5 : topK;
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(actualTopK)
                .similarityThresholdAll()
                .filterExpression("conversationId == '" + escapeFilterValue(conversationId)
                        + "' && chunkType == '" + CHILD_CHUNK_TYPE + "'")
                .build());
    }

    private List<Document> searchLegacyChunks(String query, String conversationId, int topK) {
        int actualTopK = topK <= 0 ? 5 : topK;
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(actualTopK)
                .similarityThresholdAll()
                .filterExpression("conversationId == '" + escapeFilterValue(conversationId) + "'")
                .build());
    }

    private RagChunk loadParentChunk(String conversationId, String parentChunkId, Document childDocument) {
        List<RagChunk> chunks = jdbcTemplate.query("""
                        SELECT text, metadata
                        FROM rag_parent_chunks
                        WHERE conversation_id = ? AND parent_chunk_id = ?
                        ORDER BY parent_index
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> metadata = readMetadata(rs.getString("metadata"));
                    metadata.put("matchedChildId", childDocument.getId());
                    metadata.put("matchedChildScore", childDocument.getScore());
                    return new RagChunk(rs.getString("text"), childDocument.getScore(), metadata);
                },
                conversationId,
                parentChunkId);
        return chunks.stream()
                .findFirst()
                .orElseGet(() -> new RagChunk(childDocument.getText(), childDocument.getScore(), childDocument.getMetadata()));
    }

    private String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String writeChunkIds(List<String> chunkIds) {
        try {
            return objectMapper.writeValueAsString(chunkIds);
        } catch (Exception e) {
            throw new IllegalStateException("序列化文档片段ID失败", e);
        }
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("序列化父文档片段元数据失败", e);
        }
    }

    private Map<String, Object> readMetadata(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("读取父文档片段元数据失败", e);
        }
    }

    private List<String> readChunkIds(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("读取文档片段ID失败", e);
        }
    }

    public record UploadResult(String documentId, String fileName, int pageDocumentCount, int chunkCount) {
    }

    public record KnowledgeDocumentSummary(String documentId, String fileName, int chunkCount) {
    }

    public record RagChunk(
            String text,
            Double score,
            Map<String, Object> metadata
    ) {
    }

    private record ParentChildChunks(List<ParentChunk> parentChunks, List<Document> childChunks) {
    }

    private record ParentChunk(String id, int index, String text, Map<String, Object> metadata) {
    }
}
