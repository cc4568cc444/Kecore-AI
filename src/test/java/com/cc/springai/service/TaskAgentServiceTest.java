package com.cc.springai.service;

import com.cc.springai.tools.BasicTools;
import com.cc.springai.tools.CalculationTools;
import com.cc.springai.tools.DateTimeTools;
import com.cc.springai.tools.RagTools;
import com.cc.springai.config.ShellToolConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TaskAgentServiceTest {

    private final TaskAgentService service = new TaskAgentService(
            mock(OpenAiChatModel.class),
            mock(ToolCallingManager.class),
            new DateTimeTools(),
            new BasicTools(new ShellToolConfiguration.ShellToolProperties()),
            new CalculationTools(),
            mock(RagTools.class),
            mock(RagService.class)
    );

    @Test
    void capsMaxRoundsByRole() {
        assertThat(service.effectiveMaxRounds(TaskAgentService.TaskRole.EXPLORER, 100)).isEqualTo(15);
        assertThat(service.effectiveMaxRounds(TaskAgentService.TaskRole.REVIEWER, 100)).isEqualTo(20);
        assertThat(service.effectiveMaxRounds(TaskAgentService.TaskRole.PLANNER, 100)).isEqualTo(10);
        assertThat(service.effectiveMaxRounds(TaskAgentService.TaskRole.TESTER, 100)).isEqualTo(25);
        assertThat(service.effectiveMaxRounds(TaskAgentService.TaskRole.IMPLEMENTER, 100)).isEqualTo(50);
    }

    @Test
    void defaultsMaxRoundsToTen() {
        assertThat(service.effectiveMaxRounds(TaskAgentService.TaskRole.EXPLORER, null)).isEqualTo(10);
        assertThat(service.effectiveMaxRounds(TaskAgentService.TaskRole.IMPLEMENTER, null)).isEqualTo(10);
    }

    @Test
    void onlyMutationCapableRolesRequireApproval() {
        assertThat(service.isAutoApprovable("explorer", false)).isTrue();
        assertThat(service.isAutoApprovable("reviewer", true)).isTrue();
        assertThat(service.isAutoApprovable("planner", false)).isTrue();
        assertThat(service.isAutoApprovable("tester", false)).isFalse();
        assertThat(service.isAutoApprovable("implementer", false)).isFalse();
    }

    @Test
    void backgroundMutationRolesAreAutoApprovableBecauseTheyAreRejectedByTool() {
        assertThat(service.isAutoApprovable("tester", true)).isTrue();
        assertThat(service.isAutoApprovable("implementer", true)).isTrue();
    }
}
