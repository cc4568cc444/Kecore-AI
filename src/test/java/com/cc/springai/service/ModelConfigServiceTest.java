package com.cc.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ModelConfigServiceTest {

    private final ModelConfigService service = new ModelConfigService(
            mock(JdbcTemplate.class), mock(OpenAiChatModel.class), new ObjectMapper());

    @Test
    void acceptsReadableModelIds() {
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(service, "validateModelId", "deepseekv2"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ReflectionTestUtils.invokeMethod(service, "validateModelId", "deepseek-v2_free"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeModelIds() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateModelId", "Deep Seek V2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("模型 ID 必须以字母或数字开头，且只能包含小写字母、数字、-、_，长度不超过 64 位");
    }
}
