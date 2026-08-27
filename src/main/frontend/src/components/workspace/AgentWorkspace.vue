<script setup>
import { computed, watch } from "vue";
import AppIcon from "../common/AppIcon.vue";
import FloatingSelect from "../common/FloatingSelect.vue";
import { api } from "../../services/api";
import { DEFAULT_AGENT_WORKING_DIRECTORY } from "../../services/config";
import { normalizeApprovalMode, normalizeSandboxEnabled } from "../../utils/session";
import { useSessionStore } from "../../stores/session";
import { useWorkspaceStore } from "../../stores/workspace";

const sessions = useSessionStore();
const workspace = useWorkspaceStore();

const session = computed(() => sessions.activeSession);
const shouldShowContextMeter = computed(() => sessions.mode === "agent" && Boolean(session.value?.workingDirectory));
const isLightCompacting = computed(() => workspace.contextCompactionMode === "light");
const lightCompactionLabel = computed(() => {
  if (isLightCompacting.value) {
    return "轻量压缩 正在执行";
  }
  return `轻量压缩 ${session.value?.lightCompactionEnabled ? "已启用" : "已关闭"}`;
});
const lightCompactionDetail = computed(() => {
  if (isLightCompacting.value) {
    return "正在标记旧工具输出并刷新上下文进度";
  }
  return `发送后 ${session.value?.lightCompactionHours || 6} 小时自动触发`;
});
const approvalModeOptions = [
  { value: "work-auto", label: "Work Auto 模式" },
  { value: "default", label: "严格模式" },
  { value: "auto", label: "Auto 模式（最大权限）" }
];

function updateWorkingDirectory(value) {
  if (!session.value) {
    return;
  }
  session.value.workingDirectory = value || DEFAULT_AGENT_WORKING_DIRECTORY;
  sessions.saveSessions();
}

async function saveApprovalSettings() {
  if (!session.value) {
    return;
  }
  session.value.workingDirectory = session.value.workingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY;
  session.value.approvalMode = normalizeApprovalMode(session.value.approvalMode);
  session.value.sandboxEnabled = normalizeSandboxEnabled(session.value.sandboxEnabled, session.value.approvalMode);
  sessions.saveSessions();
  try {
    await api.updateAgentApprovalMode(
      session.value.id,
      session.value.approvalMode,
      session.value.sandboxEnabled,
      session.value.workingDirectory
    );
    sessions.setStatus("审批模式已更新");
  } catch (error) {
    sessions.setStatus("审批模式本地已更新，后端同步失败");
  }
}

async function saveWorkspaceSettings() {
  await saveApprovalSettings();
  workspace.loadContextUsage();
}

function mcpTools(server) {
  return Array.isArray(server?.tools) ? server.tools : [];
}

function mcpLoadStateText(server) {
  if (server?.initialized || server?.loaded || server?.loadState === "loaded") {
    return "已加载";
  }
  if (server?.loadState === "configured_not_loaded") {
    return "配置未加载";
  }
  if (server?.status) {
    return server.status;
  }
  return "未初始化";
}

function mcpToolLabel(tool) {
  return tool?.title || tool?.name || "";
}

function mcpDisplayName(server) {
  return server?.configuredName || server?.name || server?.serverName || server?.id || "未命名 MCP";
}

function mcpMeta(server) {
  const tools = mcpTools(server);
  return [
    mcpLoadStateText(server),
    server?.transportType,
    `${server?.toolCount || tools.length || 0} 工具`
  ].filter(Boolean).join(" · ");
}

function mcpToolPreview(server) {
  const tools = mcpTools(server);
  return tools.map(mcpToolLabel).filter(Boolean).slice(0, 3).join("、")
    || server?.target
    || "无工具";
}

function mcpTooltip(server) {
  const tools = mcpTools(server);
  const lines = [
    `连接名：${mcpDisplayName(server)}`,
    server?.serverName ? `Server：${server.serverName}` : "",
    server?.title ? `标题：${server.title}` : "",
    server?.version ? `版本：${server.version}` : "",
    server?.transportType ? `类型：${server.transportType}` : "",
    server?.target ? `目标：${server.target}` : "",
    `状态：${mcpLoadStateText(server)}`,
    `工具数：${server?.toolCount || tools.length || 0}`
  ].filter(Boolean);
  if (tools.length) {
    lines.push("工具：");
    tools.slice(0, 20).forEach((tool) => {
      const label = mcpToolLabel(tool) || "unnamed";
      lines.push(`- ${label}${tool.description ? ` - ${tool.description}` : ""}`);
    });
  }
  return lines.join("\n");
}

function mcpReady(server) {
  return Boolean(server?.initialized || server?.loaded || server?.loadState === "loaded");
}

watch(
  () => [sessions.mode, session.value?.id, session.value?.workingDirectory],
  ([mode, sessionId, workingDirectory]) => {
    if (mode === "agent" && sessionId && workingDirectory) {
      workspace.loadContextUsage();
    }
  },
  { immediate: true }
);
</script>

<template>
  <section v-if="session" class="agent-workspace" id="agentWorkspace" :hidden="sessions.mode !== 'agent' || !sessions.workspaceExpanded">
    <div class="workspace-body" id="workspaceBody">
      <div class="workspace-config">
        <label for="workingDirectoryInput">默认工作目录</label>
        <div class="workspace-input-row">
          <input
            id="workingDirectoryInput"
            type="text"
            :value="session?.workingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY"
            placeholder="输入优先工作的目录路径，例如 D:\AgentWorkshop"
            @input="updateWorkingDirectory($event.target.value)"
          >
          <button class="workspace-button icon-text-button" id="saveWorkspaceButton" type="button" @click="saveWorkspaceSettings">
            <AppIcon name="save" :size="15" />
            <span>使用</span>
          </button>
        </div>
      </div>
      <div class="workspace-config">
        <label for="approvalModeSelect">审批模式</label>
        <FloatingSelect
          id="approvalModeSelect"
          v-model="session.approvalMode"
          :options="approvalModeOptions"
          @change="saveApprovalSettings"
        />
        <label class="workspace-checkbox" for="sandboxEnabledInput">
          <input
            id="sandboxEnabledInput"
            type="checkbox"
            v-model="session.sandboxEnabled"
            :disabled="session.approvalMode === 'auto'"
            @change="saveApprovalSettings"
          >
          <span>启动沙盒模式</span>
        </label>
      </div>
      <div class="workspace-actions">
        <button class="workspace-button secondary icon-text-button" id="memoryButton" type="button" @click="workspace.openMemory">
          <AppIcon name="brain" :size="15" />
          <span>记忆</span>
        </button>
        <button class="workspace-button secondary icon-text-button" id="skillsButton" type="button" @click="workspace.openSkills">
          <AppIcon name="bot" :size="15" />
          <span>Skills</span>
        </button>
        <button class="workspace-button secondary icon-text-button" id="contextPreviewButton" type="button" @click="workspace.openContextDrawer">
          <AppIcon name="fileText" :size="15" />
          <span>上下文</span>
        </button>
        <button
          class="workspace-button secondary icon-text-button"
          id="compactContextButton"
          type="button"
          :disabled="workspace.contextCompacting"
          @click="workspace.compactContext(false)"
        >
          <AppIcon name="refreshCw" :size="15" />
          <span>{{ workspace.contextCompactionMode === 'full' ? '压缩中' : '压缩' }}</span>
        </button>
      </div>
      <div class="light-compaction-panel" id="lightCompactionPanel">
        <div class="light-compaction-copy">
          <span id="lightCompactionLabel">{{ lightCompactionLabel }}</span>
          <span id="lightCompactionDetail">{{ lightCompactionDetail }}</span>
        </div>
        <div class="context-meter-track light"><span id="lightCompactionBar"></span></div>
        <div class="light-compaction-controls">
          <label class="workspace-checkbox" for="lightCompactionEnabledInput">
            <input id="lightCompactionEnabledInput" type="checkbox" v-model="session.lightCompactionEnabled" @change="sessions.saveSessions">
            <span>自动轻量压缩</span>
          </label>
          <label class="light-compaction-hours" for="lightCompactionHoursInput">
            <span>触发间隔</span>
            <input id="lightCompactionHoursInput" type="number" min="0.1" max="168" step="0.5" v-model.number="session.lightCompactionHours" @change="sessions.saveSessions">
            <span>小时</span>
          </label>
          <button
            class="workspace-button secondary icon-text-button"
            id="lightCompactContextButton"
            type="button"
            :disabled="workspace.contextCompacting"
            @click="workspace.compactContext(true)"
          >
            <AppIcon name="refreshCw" :size="15" />
            <span>{{ isLightCompacting ? '轻量压缩中' : '轻量压缩' }}</span>
          </button>
        </div>
      </div>
      <div class="context-meter" id="contextMeter" :hidden="!shouldShowContextMeter">
        <div class="context-meter-copy">
          <span id="contextUsageLabel">{{ workspace.contextUsageLabel }}</span>
          <span id="contextUsageDetail">{{ workspace.contextUsageDetail }}</span>
        </div>
        <div class="context-meter-track"><span id="contextUsageBar" :style="{ width: `${workspace.contextPercent}%` }"></span></div>
        <div class="context-meter-copy">
          <span id="autoCompactLabel">{{ workspace.autoCompactionLabel }}</span>
          <span id="autoCompactDetail">{{ workspace.autoCompactionDetail }}</span>
        </div>
        <div class="context-meter-track auto"><span id="autoCompactBar" :style="{ width: `${workspace.autoCompactionPercent}%` }"></span></div>
      </div>
      <div class="mcp-servers" id="mcpServers">
        <span v-if="!workspace.mcpServers.length" class="mcp-empty">MCP：未连接 server</span>
        <template v-else>
          <span class="mcp-label">MCP</span>
          <span
            v-for="server in workspace.mcpServers"
            :key="server.configuredName || server.name || server.serverName || server.id"
            class="mcp-server"
            :class="mcpReady(server) ? 'ready' : 'offline'"
            :title="mcpTooltip(server)"
          >
            <span class="mcp-server-name">{{ mcpDisplayName(server) }}</span>
            <span class="mcp-server-meta">{{ mcpMeta(server) }}</span>
            <span class="mcp-server-tools">{{ mcpToolPreview(server) }}</span>
          </span>
        </template>
      </div>
    </div>
  </section>
</template>
