<script setup>
import { reactive, watch } from "vue";
import FloatingSelect from "../common/FloatingSelect.vue";
import { DEFAULT_AGENT_WORKING_DIRECTORY } from "../../services/config";
import { normalizeApprovalMode, normalizeSandboxEnabled } from "../../utils/session";
import { useSessionStore } from "../../stores/session";

const sessions = useSessionStore();
const form = reactive({
  workingDirectory: DEFAULT_AGENT_WORKING_DIRECTORY,
  approvalMode: "work-auto",
  sandboxEnabled: true
});
const approvalModeOptions = [
  { value: "work-auto", label: "Work Auto 模式" },
  { value: "default", label: "严格模式" },
  { value: "auto", label: "Auto 模式（最大权限）" }
];

watch(() => sessions.workspaceModalOpen, (open) => {
  if (!open) {
    return;
  }
  const session = sessions.activeSession;
  form.workingDirectory = session?.workingDirectory || sessions.defaultWorkingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY;
  form.approvalMode = normalizeApprovalMode(session?.approvalMode);
  form.sandboxEnabled = normalizeSandboxEnabled(session?.sandboxEnabled, form.approvalMode);
});

function chooseApprovalMode(value) {
  form.approvalMode = normalizeApprovalMode(value);
  form.sandboxEnabled = normalizeSandboxEnabled(form.sandboxEnabled, form.approvalMode);
}

function submit() {
  if (sessions.workspaceModalCreateNew) {
    sessions.startNewSession({
      workingDirectory: form.workingDirectory,
      approvalMode: form.approvalMode,
      sandboxEnabled: form.sandboxEnabled
    });
    return;
  }
  sessions.updateActiveSession({
    workingDirectory: form.workingDirectory,
    approvalMode: form.approvalMode,
    sandboxEnabled: normalizeSandboxEnabled(form.sandboxEnabled, form.approvalMode)
  });
  sessions.closeWorkspaceModal();
}
</script>

<template>
  <div class="workspace-modal" id="workspaceModal" :hidden="!sessions.workspaceModalOpen" @click.self="sessions.closeWorkspaceModal">
    <form class="workspace-dialog" id="workspaceForm" role="dialog" aria-modal="true" aria-labelledby="workspaceTitle" @submit.prevent="submit">
      <div class="workspace-dialog-header">
        <h2 id="workspaceTitle">设置工作目录</h2>
        <p>Agent 将在此目录中读取文件和执行任务。</p>
      </div>
      <label for="newSessionWorkingDirectory">工作目录</label>
      <input id="newSessionWorkingDirectory" v-model="form.workingDirectory" type="text" required autocomplete="off">
      <div class="workspace-dialog-field" id="newSessionApprovalModeField">
        <label for="newSessionApprovalMode">审批模式</label>
        <FloatingSelect
          id="newSessionApprovalMode"
          v-model="form.approvalMode"
          :options="approvalModeOptions"
          @change="chooseApprovalMode"
        />
      </div>
      <label class="workspace-dialog-check" id="newSessionSandboxField" for="newSessionSandboxEnabled">
        <input id="newSessionSandboxEnabled" v-model="form.sandboxEnabled" type="checkbox" :disabled="form.approvalMode === 'auto'">
        <span>启动沙盒模式</span>
      </label>
      <div class="workspace-dialog-actions">
        <button class="secondary" id="cancelWorkspaceButton" type="button" @click="sessions.closeWorkspaceModal">取消</button>
        <button id="confirmWorkspaceButton" type="submit">{{ sessions.workspaceModalCreateNew ? '开始新会话' : '确认目录' }}</button>
      </div>
    </form>
  </div>
</template>
