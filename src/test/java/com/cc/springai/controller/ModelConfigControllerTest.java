package com.cc.springai.controller;

import com.cc.springai.service.ModelConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ModelConfigControllerTest {

    @Test
    void delegatesRenameUsingPathAsOriginalIdAndBodyAsTargetId() {
        ModelConfigService service = mock(ModelConfigService.class);
        ModelConfigController controller = new ModelConfigController(service);
        ModelConfigService.ModelConfigRequest request = new ModelConfigService.ModelConfigRequest(
                "deepseekv2", "deepseek", "OpenAI Compatible", "https://example.com",
                "/v1/chat/completions", "", "deepseek-v4-flash", null,
                null, null, null, true);

        controller.updateModel("old-uuid", request);

        verify(service).update("old-uuid", request);
    }

    @Test
    void returnsReadableBadRequestForValidationErrors() {
        ModelConfigController controller = new ModelConfigController(mock(ModelConfigService.class));

        var response = controller.handleInvalidModel(new IllegalArgumentException("模型 ID 已存在：deepseek"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("模型 ID 已存在：deepseek");
    }
}
