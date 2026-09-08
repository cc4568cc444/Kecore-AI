<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { useChatStore } from "../../stores/chat";
import { useSessionStore } from "../../stores/session";
import { useWorkspaceStore } from "../../stores/workspace";
import MessageBubble from "./MessageBubble.vue";

const sessions = useSessionStore();
const chat = useChatStore();
const workspace = useWorkspaceStore();
const messagesEl = ref(null);

const SCROLL_BOTTOM_TOLERANCE = 48;
const messages = computed(() => sessions.activeSession?.messages || []);
const activeSessionId = computed(() => sessions.activeSession?.id || "");
const messageSendDisabled = computed(() => chat.running || workspace.contextCompacting);
const pinnedToBottom = ref(true);
let scrollFrame = 0;

const suggestions = computed(() => {
  if (sessions.mode === "finance") {
    return [
      { label: "跨年趋势", prompt: "Apple 的 revenue 从 2022 到 2024 年如何变化？请计算年度变化率。" },
      { label: "同行对比", prompt: "比较 Microsoft 和 Apple 在 2024 财年的营业利润率，并说明差异。" },
      { label: "多文档分析", prompt: "比较 NVIDIA 与 AMD 从 2022 到 2024 年的 revenue 增长趋势，并引用各年证据。" }
    ];
  }
  if (sessions.mode === "paper") {
    return [
      { label: "查询实验指标", prompt: "这篇论文在主要测试集上报告了哪些核心实验指标？" },
      { label: "解释方法改进", prompt: "论文提出的方法相比基线做了哪些关键改进？" },
      { label: "核对数据规模", prompt: "论文使用的数据集规模和训练配置分别是什么？" }
    ];
  }
  if (sessions.mode === "agent") {
    return [
      { label: "整理项目结构", prompt: "请阅读当前项目结构，总结主要模块和启动方式。" },
      { label: "修复一个问题", prompt: "请定位并修复一个前端交互问题，完成后说明验证方式。" },
      { label: "生成计划", prompt: "请先分析代码，再给出一个可执行的重构计划。" }
    ];
  }
  return [
    { label: "介绍 Kecore AI", prompt: "用三句话介绍一下 Kecore AI" },
    { label: "写接口示例", prompt: "帮我写一个 Java 后端接口示例" },
    { label: "解释流式输出", prompt: "解释一下大模型流式输出的原理" }
  ];
});

function valueSize(value) {
  if (value === null || value === undefined) {
    return 0;
  }
  if (typeof value === "string") {
    return value.length;
  }
  try {
    return JSON.stringify(value).length;
  } catch {
    return String(value).length;
  }
}

function agentTraceSignal(message) {
  const batches = (message?.toolBatches || []).map((batch) => {
    const tools = (batch.tools || []).map((tool) => [
      tool.name || "",
      valueSize(tool.arguments),
      valueSize(tool.output),
      tool.progress?.length || 0,
      valueSize(tool.progress)
    ].join(","));
    return [
      batch.runId || "",
      batch.status || "",
      tools.length,
      tools.join("|"),
      valueSize(batch.interaction)
    ].join(",");
  });
  const groups = (message?.taskGroups || []).map((group) => [
    group.taskGroupId || "",
    group.status || "",
    group.completedCount || 0,
    group.totalCount || 0
  ].join(","));
  const progress = message?.agentRoundProgress
    ? [
        message.agentRoundProgress.status || "",
        message.agentRoundProgress.currentRound || 0,
        message.agentRoundProgress.completedRounds || 0,
        message.agentRoundProgress.toolRoundLimit || message.agentRoundProgress.maxRounds || 0,
        message.agentRoundProgress.percent || 0
      ].join(",")
    : "";
  return [
    message?.activities?.length || 0,
    valueSize(message?.activities),
    batches.join(";"),
    groups.join(";"),
    progress
  ].join(":");
}

const scrollSignal = computed(() => {
  const list = messages.value;
  const last = list[list.length - 1];
  return [
    activeSessionId.value,
    list.length,
    last?.id || "",
    last?.text?.length || 0,
    last?.reasoning?.length || 0,
    agentTraceSignal(last),
    last?.streaming ? 1 : 0
  ].join(":");
});

function isElementPinnedToBottom() {
  const element = messagesEl.value;
  if (!element) {
    return true;
  }
  return element.scrollHeight - element.scrollTop - element.clientHeight <= SCROLL_BOTTOM_TOLERANCE;
}

function setPinnedToBottom(value) {
  pinnedToBottom.value = value;
  chat.messagesPinnedToBottom = value;
}

function syncPinnedToBottom() {
  setPinnedToBottom(isElementPinnedToBottom());
}

function scheduleScrollToBottom({ force = false } = {}) {
  if (!force && !pinnedToBottom.value) {
    return;
  }
  if (scrollFrame) {
    cancelAnimationFrame(scrollFrame);
  }
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    const element = messagesEl.value;
    if (element) {
      element.scrollTop = element.scrollHeight;
      setPinnedToBottom(true);
    }
  });
}

watch(activeSessionId, () => {
  setPinnedToBottom(true);
  nextTick(() => scheduleScrollToBottom({ force: true }));
}, { immediate: true });

watch(scrollSignal, () => {
  const shouldFollow = pinnedToBottom.value || isElementPinnedToBottom();
  nextTick(() => {
    if (shouldFollow) {
      scheduleScrollToBottom({ force: true });
    } else {
      syncPinnedToBottom();
    }
  });
});

onBeforeUnmount(() => {
  if (scrollFrame) {
    cancelAnimationFrame(scrollFrame);
  }
});

function submitSuggestion(prompt) {
  if (messageSendDisabled.value) {
    sessions.setStatus(workspace.contextCompacting ? "上下文压缩中，暂不可发送消息" : sessions.status);
    return;
  }
  chat.setPrompt(prompt);
  nextTick(() => document.getElementById("promptInput")?.focus());
}

function isLatestUserMessage(index) {
  const list = messages.value;
  return list[index]?.role === "user"
    && index === list.findLastIndex((message) => message?.role === "user");
}

function retryUserMessage(message) {
  if (!message?.text || messageSendDisabled.value) {
    if (workspace.contextCompacting) {
      sessions.setStatus("上下文压缩中，暂不可重试");
    }
    return;
  }
  chat.regenerateUserMessage(message);
}

function messageKey(message, index) {
  return `${message.id || message.createdAt || "message"}-${index}`;
}
</script>

<template>
  <section ref="messagesEl" class="messages" id="messages" aria-live="polite" @scroll.passive="syncPinnedToBottom">
    <div v-if="!messages.length" class="empty-state" id="emptyState">
      <div class="empty-logo" aria-hidden="true"><span>K</span></div>
      <h2 id="emptyTitle">{{ sessions.emptyTitle }}</h2>
      <p class="welcome-description">{{ sessions.mode === 'finance' ? '从财务指标到年度趋势，让问题找到有据可循的答案。' : sessions.mode === 'agent' ? '描述任务，或打开工作空间连接你的项目。' : '提一个问题，分享一个想法，或一起完成一件事。' }}</p>
      <div class="suggestions" id="chatSuggestions">
        <button
          v-for="item in suggestions"
          :key="item.prompt"
          type="button"
          :data-prompt="item.prompt"
          :disabled="messageSendDisabled"
          :title="workspace.contextCompacting ? '上下文压缩中，暂不可发送' : item.label"
          @click="submitSuggestion(item.prompt)"
        >
          {{ item.label }}
        </button>
      </div>
    </div>
    <MessageBubble
      v-for="(message, index) in messages"
      :key="messageKey(message, index)"
      :message="message"
      :mode="sessions.activeSession?.mode"
      :show-retry="isLatestUserMessage(index)"
      :retry-disabled="messageSendDisabled"
      @retry="retryUserMessage(message)"
    />
  </section>
</template>
