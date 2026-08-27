<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import AppIcon from "../common/AppIcon.vue";
import { api } from "../../services/api";
import { useChatStore } from "../../stores/chat";
import { useWorkspaceStore } from "../../stores/workspace";
import { agentRoundProgressStatusText, parseTaskOutput, parseToolArguments, renderToolOutputText, taskRoleLabel, taskStatusLabel, toolBatchStatusText } from "../../utils/agent";
import { renderMarkdown, tailTextPreview } from "../../utils/markdown";
import { estimateTokensFromText, normalizeTokenUsage, validLatencyMs, validTokenCount } from "../../utils/session";

const TOOL_RESULT_COLLAPSE_MIN_CHARS = 1200;
const TOOL_RESULT_COLLAPSE_MIN_LINES = 16;

const props = defineProps({
  message: {
    type: Object,
    required: true
  },
  mode: {
    type: String,
    default: ""
  },
  showRetry: {
    type: Boolean,
    default: false
  },
  retryDisabled: {
    type: Boolean,
    default: false
  }
});

const emit = defineEmits(["retry"]);
const chat = useChatStore();
const workspace = useWorkspaceStore();
const html = computed(() => renderMarkdown(props.message.text));
const agentActionDisabled = computed(() => chat.running || workspace.contextCompacting);
const expandedToolResults = ref(new Set());
const agentTraceEl = ref(null);
const agentTracePinnedToBottom = ref(true);
const nowTick = ref(Date.now());
let rateTimer = null;
let traceScrollFrame = 0;
let traceResizeObserver = null;

const TRACE_SCROLL_BOTTOM_TOLERANCE = 32;

function isLiveMessage(message) {
  return Boolean(message.streaming || message.thinking);
}

watch(() => isLiveMessage(props.message), (live) => {
  clearInterval(rateTimer);
  nowTick.value = Date.now();
  rateTimer = live ? setInterval(() => {
    nowTick.value = Date.now();
  }, 500) : null;
}, { immediate: true });

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

const agentTraceSignal = computed(() => {
  const message = props.message;
  const batches = (message.toolBatches || []).map((batch) => {
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
  const groups = (message.taskGroups || []).map((group) => [
    group.taskGroupId || "",
    group.status || "",
    group.completedCount || 0,
    group.totalCount || 0
  ].join(","));
  const roundProgress = message.agentRoundProgress
    ? [
        message.agentRoundProgress.status || "",
        message.agentRoundProgress.currentRound || 0,
        message.agentRoundProgress.completedRounds || 0,
        message.agentRoundProgress.toolRoundLimit || message.agentRoundProgress.maxRounds || 0,
        message.agentRoundProgress.percent || 0
      ].join(",")
    : "";
  return [
    message.activities?.length || 0,
    valueSize(message.activities),
    batches.join(";"),
    groups.join(";"),
    roundProgress
  ].join(":");
});

function isAgentTracePinnedToBottom() {
  const element = agentTraceEl.value;
  if (!element) {
    return true;
  }
  return element.scrollHeight - element.scrollTop - element.clientHeight <= TRACE_SCROLL_BOTTOM_TOLERANCE;
}

function syncAgentTracePinnedToBottom() {
  agentTracePinnedToBottom.value = isAgentTracePinnedToBottom();
}

function scheduleAgentTraceScrollToBottom({ force = false } = {}) {
  if (!force && !agentTracePinnedToBottom.value) {
    return;
  }
  if (traceScrollFrame) {
    cancelAnimationFrame(traceScrollFrame);
  }
  traceScrollFrame = requestAnimationFrame(() => {
    traceScrollFrame = requestAnimationFrame(() => {
      traceScrollFrame = 0;
      const element = agentTraceEl.value;
      if (element) {
        element.scrollTop = element.scrollHeight;
        agentTracePinnedToBottom.value = true;
      }
    });
  });
}

function stopTraceResizeObserver() {
  traceResizeObserver?.disconnect();
  traceResizeObserver = null;
}

watch(agentTraceEl, (element) => {
  stopTraceResizeObserver();
  agentTracePinnedToBottom.value = true;
  if (!element) {
    return;
  }
  traceResizeObserver = new ResizeObserver(() => {
    if (agentTracePinnedToBottom.value || isAgentTracePinnedToBottom()) {
      scheduleAgentTraceScrollToBottom({ force: true });
    }
  });
  traceResizeObserver.observe(element);
  scheduleAgentTraceScrollToBottom({ force: true });
});

watch(agentTraceSignal, () => {
  const shouldFollow = agentTracePinnedToBottom.value || isAgentTracePinnedToBottom();
  nextTick(() => {
    if (shouldFollow) {
      scheduleAgentTraceScrollToBottom({ force: true });
    } else {
      syncAgentTracePinnedToBottom();
    }
  });
}, { immediate: true });

onBeforeUnmount(() => {
  clearInterval(rateTimer);
  stopTraceResizeObserver();
  if (traceScrollFrame) {
    cancelAnimationFrame(traceScrollFrame);
  }
});

function formatLatency(ms) {
  const latency = validLatencyMs(ms);
  if (latency === null) {
    return null;
  }
  const totalSeconds = Math.max(0, Math.round(latency / 1000));
  const seconds = totalSeconds % 60;
  const totalMinutes = Math.floor(totalSeconds / 60);
  const minutes = totalMinutes % 60;
  const hours = Math.floor(totalMinutes / 60);
  if (hours > 0) {
    return `${hours}h:${String(minutes).padStart(2, "0")}m:${String(seconds).padStart(2, "0")}s`;
  }
  if (totalMinutes > 0) {
    return `${totalMinutes}m:${String(seconds).padStart(2, "0")}s`;
  }
  return `${seconds}s`;
}

function formatCompactTokenCount(value) {
  const count = validTokenCount(value);
  if (count === null) {
    return "-";
  }
  if (count < 1000) {
    return String(count);
  }
  const unit = count >= 1000000
    ? { threshold: 1000000, suffix: "M" }
    : { threshold: 1000, suffix: "k" };
  const scaled = count / unit.threshold;
  const rounded = scaled >= 100 ? Math.round(scaled) : Math.round(scaled * 10) / 10;
  return `${String(rounded).replace(/\.0$/, "")}${unit.suffix}`;
}

function tokenMetrics(message) {
  const usage = normalizeTokenUsage(message.tokenUsage);
  const inputTokens = usage?.inputTokens ?? validTokenCount(message.inputTokenEstimate);
  let outputTokens = usage?.outputTokens ?? validTokenCount(message.outputTokenEstimate);
  if (outputTokens === null && message.text) {
    outputTokens = estimateTokensFromText(message.text);
  }
  let totalTokens = usage?.totalTokens ?? null;
  if (totalTokens === null && inputTokens !== null && outputTokens !== null) {
    totalTokens = inputTokens + outputTokens;
  } else if (totalTokens === null && inputTokens !== null) {
    totalTokens = inputTokens;
  }
  return { totalTokens, inputTokens, outputTokens };
}

function formatTokenUsage(message) {
  const usage = tokenMetrics(message);
  return [
    `tokens ${formatCompactTokenCount(usage.totalTokens)}`,
    `in ${formatCompactTokenCount(usage.inputTokens)}`,
    `out ${formatCompactTokenCount(usage.outputTokens)}`
  ].join(" · ");
}

function formatTokenRate(message) {
  const outputTokens = validTokenCount(tokenMetrics(message).outputTokens);
  const startedAt = Number(message.firstTokenAt || message.startedAt || 0);
  const endedAt = Number(isLiveMessage(message) ? nowTick.value : (message.completedAt || message.lastTokenAt || Date.now()));
  if (!outputTokens || !startedAt || endedAt <= startedAt) {
    return "0.0 tok/s";
  }
  const seconds = Math.max(0.25, (endedAt - startedAt) / 1000);
  return `${(outputTokens / seconds).toFixed(1)} tok/s`;
}

function messageMode(message) {
  return message.mode || props.mode;
}

function isFinanceTiming(message) {
  return messageMode(message) === "finance";
}

function isPaperTiming(message) {
  return messageMode(message) === "paper";
}

function isRagTiming(message) {
  return isFinanceTiming(message) || isPaperTiming(message);
}

function timingText(message) {
  if (message.role !== "assistant") {
    return "";
  }
  const liveNow = isLiveMessage(message) ? nowTick.value : Date.now();
  const latency = validLatencyMs(message.latencyMs)
    ?? (message.startedAt ? Math.max(0, liveNow - message.startedAt) : 0);
  const parts = [`time ${formatLatency(latency)}`];
  if (isRagTiming(message)) {
    parts.push(`translate ${formatLatency(message.translationMs ?? 0)}`);
    parts.push(`recall ${formatLatency(message.retrievalMs ?? 0)}`);
    parts.push(`model ${formatLatency(message.modelResponseMs ?? 0)}`);
  }
  parts.push(formatTokenUsage(message));
  parts.push(formatTokenRate(message));
  return parts.join(" · ");
}

function retryMessage() {
  if (!props.retryDisabled) {
    emit("retry");
  }
}

function roundProgress(progress) {
  if (!progress) {
    return null;
  }
  const limit = Math.max(1, Number(progress.toolRoundLimit || progress.maxRounds || 1));
  const completed = Math.max(0, Math.min(Number(progress.completedRounds || 0), limit));
  const current = Math.max(1, Math.min(Number(progress.currentRound || completed + 1), limit));
  const percent = Math.max(0, Math.min(Number(progress.percent ?? ((completed / limit) * 100)), 100));
  const status = progress.status || "running";
  return {
    completed,
    current,
    limit,
    percent,
    status,
    title: status === "completed" ? `执行轮次 ${completed}/${limit}` : `执行轮次 ${current}/${limit}`,
    label: status === "completed" ? "完成" : agentRoundProgressStatusText(status)
  };
}

function toolOutputText(output) {
  return renderToolOutputText(output) || "(无输出)";
}

function shouldCollapseToolResult(text) {
  return text.length > TOOL_RESULT_COLLAPSE_MIN_CHARS
    || text.split(/\r\n|\r|\n/).length > TOOL_RESULT_COLLAPSE_MIN_LINES;
}

function toolResultKey(batch, tool, toolIndex) {
  return `${batch.runId || "batch"}-${toolIndex}-${tool.name || "tool"}-${toolOutputText(tool.output).length}`;
}

function isToolResultExpanded(key) {
  return expandedToolResults.value.has(key);
}

function toggleToolResult(key) {
  const next = new Set(expandedToolResults.value);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  expandedToolResults.value = next;
}

function openInteraction(batch) {
  chat.openInteraction(batch.interaction || { runId: batch.runId, type: batch.status === "loop_limit" ? "choice" : "text" });
}

async function handleBubbleClick(event) {
  const imageLink = event.target.closest?.("a[data-image-url]");
  if (imageLink) {
    event.preventDefault();
    chat.openImagePreview(imageLink.getAttribute("data-image-url"));
    return;
  }
  const fileLink = event.target.closest?.("a[data-local-file-path]");
  if (fileLink) {
    event.preventDefault();
    try {
      await api.openLocalFile(fileLink.getAttribute("data-local-file-path"));
    } catch (error) {
      console.warn("打开本地文件失败:", error);
    }
  }
}
</script>

<template>
  <article class="message" :class="[message.role, { error: message.error }]">
    <section
      v-if="message.role === 'assistant' && (message.activities?.length || message.toolBatches?.length || message.taskGroups?.length || message.agentRoundProgress)"
      ref="agentTraceEl"
      class="agent-trace"
      @scroll.passive="syncAgentTracePinnedToBottom"
    >
      <div v-if="roundProgress(message.agentRoundProgress)" class="agent-round-progress compact" :class="roundProgress(message.agentRoundProgress).status">
        <div class="agent-round-progress-head">
          <strong>{{ roundProgress(message.agentRoundProgress).title }}</strong>
          <span>{{ roundProgress(message.agentRoundProgress).label }}</span>
        </div>
        <div
          class="agent-round-progress-track"
          role="progressbar"
          :aria-valuemin="0"
          :aria-valuemax="roundProgress(message.agentRoundProgress).limit"
          :aria-valuenow="roundProgress(message.agentRoundProgress).completed"
        >
          <div class="agent-round-progress-fill" :style="{ width: `${roundProgress(message.agentRoundProgress).percent}%` }"></div>
        </div>
      </div>
      <div v-if="message.activities?.length" class="agent-activity">
        <div class="agent-activity-title">执行活动</div>
        <ol>
          <li v-for="(activity, index) in message.activities.slice(-8)" :key="index">{{ activity }}</li>
        </ol>
      </div>
      <div v-if="message.taskGroups?.length" class="agent-task-groups">
        <div class="agent-activity-title">后台任务组</div>
        <div v-for="group in message.taskGroups.slice(-4)" :key="group.taskGroupId" class="agent-task-group" :class="group.status || 'running'">
          <strong>group {{ String(group.taskGroupId || '').slice(0, 8) }}</strong>
          <span>{{ group.completedCount || 0 }}/{{ group.totalCount || 0 }} {{ group.status || 'running' }}</span>
        </div>
      </div>
      <div v-for="batch in message.toolBatches" :key="batch.runId || JSON.stringify(batch.tools)" class="tool-batch" :class="batch.status">
        <div class="tool-batch-status">{{ toolBatchStatusText(batch.status) }}</div>
        <div v-for="(tool, toolIndex) in batch.tools || []" :key="toolIndex">
          <div v-if="tool.name === 'task'" class="task-call" :class="`role-${String(parseToolArguments(tool.arguments).role || parseTaskOutput(tool.output).role || 'explorer').toLowerCase()}`">
            <div class="task-call-header">
              <span class="task-call-node" aria-hidden="true">{{ taskRoleLabel(parseToolArguments(tool.arguments).role || 'explorer').slice(0, 1).toUpperCase() }}</span>
              <div class="task-call-title">
                <strong>{{ taskRoleLabel(parseToolArguments(tool.arguments).role || 'explorer') }}</strong>
                <span>{{ parseToolArguments(tool.arguments).mode || 'default' }}</span>
              </div>
              <span class="task-call-status">{{ taskStatusLabel(batch.status, tool.output) }}</span>
            </div>
            <div class="task-call-section">
              <span>子任务</span>
              <p>{{ parseToolArguments(tool.arguments).prompt || '(未提供 prompt)' }}</p>
            </div>
            <div v-if="tool.progress?.length" class="task-progress">
              <span class="task-progress-title">执行进度</span>
              <ol>
                <li v-for="(item, index) in tool.progress.slice(-12)" :key="index" class="task-progress-step" :class="item.status || ''">
                  <strong>{{ item.round ? `Round ${item.round}` : 'Start' }}</strong>
                  <span>{{ item.message || item.status || '进度更新' }}</span>
                </li>
              </ol>
            </div>
            <div v-if="tool.output !== undefined && tool.output !== null" class="task-call-section result">
              <span>结果总结</span>
              <p>{{ parseTaskOutput(tool.output).summary || '(无输出)' }}</p>
            </div>
          </div>
          <div v-else class="tool-call">
            <strong>{{ tool.name }}</strong>
            <pre>{{ tool.arguments || '{}' }}</pre>
            <span v-if="tool.output !== undefined && tool.output !== null" class="tool-result-label">执行输出</span>
            <div
              v-if="tool.output !== undefined && tool.output !== null && shouldCollapseToolResult(toolOutputText(tool.output))"
              class="tool-result-wrap"
              :class="{ 'is-collapsed': !isToolResultExpanded(toolResultKey(batch, tool, toolIndex)) }"
            >
              <pre class="tool-result">{{ toolOutputText(tool.output) }}</pre>
              <button
                class="tool-result-toggle"
                type="button"
                :aria-expanded="isToolResultExpanded(toolResultKey(batch, tool, toolIndex)) ? 'true' : 'false'"
                @click="toggleToolResult(toolResultKey(batch, tool, toolIndex))"
              >{{ isToolResultExpanded(toolResultKey(batch, tool, toolIndex)) ? '收起' : '展开全部' }}</button>
            </div>
            <pre v-else-if="tool.output !== undefined && tool.output !== null" class="tool-result">{{ toolOutputText(tool.output) }}</pre>
          </div>
        </div>
        <div v-if="batch.status === 'pending'" class="tool-actions">
          <button class="tool-decision approve" type="button" :disabled="agentActionDisabled" @click="chat.decideToolAction(batch.runId, 'approve')">确认执行</button>
          <button class="tool-decision reject" type="button" :disabled="agentActionDisabled" @click="chat.decideToolAction(batch.runId, 'reject')">拒绝</button>
        </div>
        <div v-if="batch.status === 'waiting_input' || batch.status === 'loop_limit'" class="tool-actions">
          <button class="tool-decision interaction-open" type="button" :disabled="agentActionDisabled" @click="openInteraction(batch)">
            {{ batch.status === 'loop_limit' ? '打开确认' : '打开输入' }}
          </button>
        </div>
      </div>
    </section>

    <div class="bubble markdown-body" :class="{ thinking: message.thinking, error: message.error }" @click="handleBubbleClick">
      <template v-if="message.thinking && !message.text">
        <span class="thinking-label">正在思考</span>
        <span class="thinking-dots"><span></span><span></span><span></span></span>
      </template>
      <template v-else>
        <details
          v-if="message.reasoning || message.reasoningPending"
          class="reasoning-block"
          :open="message.reasoningOpen"
          @toggle="message.reasoningOpen = $event.target.open"
        >
          <summary>
            <span class="reasoning-title">思维链</span>
            <span class="reasoning-preview">{{ tailTextPreview(message.reasoning) || '正在生成...' }}</span>
          </summary>
          <div class="reasoning-content">{{ message.reasoning || '正在生成...' }}</div>
        </details>
        <div v-if="message.text" v-html="html"></div>
      </template>
    </div>

    <div v-if="message.attachments?.length" class="message-attachments">
      <span v-for="attachment in message.attachments" :key="attachment" class="message-attachment">
        <AppIcon name="paperclip" :size="12" />
        <span>{{ attachment }}</span>
      </span>
    </div>
    <div v-if="timingText(message)" class="message-timing">
      <div class="message-timing-row">{{ timingText(message) }}</div>
      <div v-if="(isFinanceTiming(message) || isPaperTiming(message)) && message.retrievalQuery" class="message-query">query {{ message.retrievalQuery }}</div>
    </div>
    <button
      v-if="showRetry && message.role === 'user'"
      class="message-retry-button"
      type="button"
      title="刷新"
      aria-label="刷新这条消息"
      :disabled="retryDisabled"
      @click="retryMessage"
    >
      <AppIcon name="refreshCw" :size="12" />
    </button>
  </article>
</template>
