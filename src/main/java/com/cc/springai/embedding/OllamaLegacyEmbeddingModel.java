package com.cc.springai.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

public class OllamaLegacyEmbeddingModel implements EmbeddingModel {

    private static final String EMBEDDINGS_PATH = "/api/embeddings";

    private final RestClient restClient;
    private final String model;
    private final int dimensions;

    public OllamaLegacyEmbeddingModel(String baseUrl, String model, int dimensions) {
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .build();
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        String requestModel = resolveModel(request);

        for (int index = 0; index < instructions.size(); index++) {
            OllamaEmbeddingResponse response = restClient.post()
                    .uri(EMBEDDINGS_PATH)
                    .body(new OllamaEmbeddingRequest(requestModel, instructions.get(index)))
                    .retrieve()
                    .body(OllamaEmbeddingResponse.class);

            if (response == null || response.embedding() == null) {
                throw new IllegalStateException("Ollama embedding response is empty.");
            }
            embeddings.add(new Embedding(response.embedding(), index));
        }

        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private String resolveModel(EmbeddingRequest request) {
        if (request.getOptions() != null
                && request.getOptions().getModel() != null
                && !request.getOptions().getModel().isBlank()) {
            return request.getOptions().getModel();
        }
        return model;
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private record OllamaEmbeddingRequest(String model, String prompt) {
    }

    private record OllamaEmbeddingResponse(float[] embedding) {
    }
}
