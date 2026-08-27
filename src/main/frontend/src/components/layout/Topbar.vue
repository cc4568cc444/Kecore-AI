<script setup>
import { computed } from "vue";
import AppIcon from "../common/AppIcon.vue";
import { useMigrationStore } from "../../stores/migration";
import { useModelStore } from "../../stores/model";
import { useSessionStore } from "../../stores/session";
import { useWorkspaceStore } from "../../stores/workspace";
import AgentWorkspace from "../workspace/AgentWorkspace.vue";

const sessions = useSessionStore();
const migration = useMigrationStore();
const models = useModelStore();
const workspace = useWorkspaceStore();

const workingDirectoryStatus = computed(() => {
  const directory = sessions.activeSession?.workingDirectory || "";
  return directory || "未选择";
});

const showWorkspaceMetrics = computed(() => {
  return sessions.mode === "agent" && Boolean(sessions.activeSession?.workingDirectory);
});

const hasStatusProblem = computed(() => {
  const status = String(sessions.status || "");
  return [
    "失败",
    "错误",
    "异常",
    "未就绪",
    "未连接",
    "连接已结束",
    "不可用",
    "请先选择"
  ].some((keyword) => status.includes(keyword));
});
</script>

<template>
  <header class="topbar">
    <div class="page-title">
      <h1 id="viewTitle">{{ sessions.title }}</h1>
      <span
        class="status"
        :class="hasStatusProblem ? 'is-not-ready' : 'is-ready'"
        id="statusText"
        role="status"
      >
        <span class="status-dot" aria-hidden="true"></span>
        <span>{{ sessions.status }}</span>
      </span>
    </div>
    <div class="topbar-actions">
      <button class="ghost-button icon-text-button" id="vueMigrationButton" type="button" title="数据导入导出" @click="migration.show">
        <AppIcon name="download" :size="15" />
        <span>数据迁移</span>
      </button>
      <button class="ghost-button icon-text-button" id="modelManageButton" type="button" title="模型管理" @click="models.openModal">
        <AppIcon name="settings" :size="15" />
        <span>模型管理</span>
      </button>
      <button class="ghost-button icon-text-button" id="clearButton" type="button" title="清空当前对话" @click="sessions.clearCurrentSession">
        <AppIcon name="trash2" :size="15" />
        <span>清空对话</span>
      </button>
      <button
        class="workspace-toggle topbar-workspace-toggle"
        id="workspaceToggle"
        type="button"
        title="展开/收起工作区"
        aria-controls="workspaceBody"
        :aria-expanded="sessions.workspaceExpanded ? 'true' : 'false'"
        :hidden="sessions.mode !== 'agent'"
        @click="sessions.workspaceExpanded = !sessions.workspaceExpanded"
      >
        <span class="workspace-toggle-main">
          <span class="workspace-toggle-label" id="workspaceToggleLabel">工作区</span>
          <span class="workspace-toggle-status" id="workingDirectoryStatus">{{ workingDirectoryStatus }}</span>
        </span>
        <span class="workspace-toggle-metrics" id="workspaceToggleMetrics" :hidden="!showWorkspaceMetrics">
          <span class="workspace-pill" id="contextUsagePill">{{ workspace.contextUsagePillLabel }}</span>
          <span class="workspace-pill auto" id="autoCompactPill">{{ workspace.autoCompactionPillLabel }}</span>
        </span>
        <AppIcon class="workspace-toggle-arrow" name="chevronDown" :size="16" />
      </button>
    </div>

    <AgentWorkspace />
  </header>
</template>
