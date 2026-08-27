<script setup>
import { computed, nextTick, ref } from "vue";
import AppIcon from "../common/AppIcon.vue";
import FloatingSelect from "../common/FloatingSelect.vue";
import { useChatStore } from "../../stores/chat";
import { useModelStore } from "../../stores/model";
import { useSessionStore } from "../../stores/session";
import { useWorkspaceStore } from "../../stores/workspace";

const chat = useChatStore();
const models = useModelStore();
const sessions = useSessionStore();
const workspace = useWorkspaceStore();
const promptInput = ref(null);

const activeSession = computed(() => sessions.activeSession);
const sendBlocked = computed(() => !chat.running && workspace.contextCompacting);
const sendTitle = computed(() => sendBlocked.value ? "上下文压缩中，暂不可发送" : (chat.running ? "停止" : "发送"));
const modelOptions = computed(() => models.modelOptions.map((option) => ({
  label: option.label,
  buttonLabel: option.shortLabel || option.label,
  value: option.id
})));
const reasoningOptions = [
  { value: "", label: "模型默认" },
  { value: "low", label: "low" },
  { value: "medium", label: "medium" },
  { value: "high", label: "high" },
  { value: "xhigh", label: "xhigh" },
  { value: "max", label: "max" }
];
const thinkingOptions = [
  { value: "", label: "模型默认" },
  { value: "enabled", label: "开启" },
  { value: "disabled", label: "关闭" }
];
const financeRetrievalOptions = [
  { value: "default", label: "默认" },
  { value: "parent-child", label: "父子策略" }
];
const runtimeReasoningEffort = computed({
  get: () => activeSession.value?.runtimeReasoningEffort || "",
  set: (value) => {
    if (!activeSession.value) {
      return;
    }
    activeSession.value.runtimeReasoningEffort = value || "";
    sessions.saveSessions();
    sessions.setStatus(value ? `本会话思考强度：${value}` : "本会话思考强度：模型默认");
  }
});
const runtimeThinkingType = computed({
  get: () => activeSession.value?.runtimeThinkingType || "",
  set: (value) => {
    if (!activeSession.value) {
      return;
    }
    activeSession.value.runtimeThinkingType = value || "";
    sessions.saveSessions();
    sessions.setStatus(value ? `本会话思考模式：${value === "enabled" ? "开启" : "关闭"}` : "本会话思考模式：模型默认");
  }
});

function resizeInput() {
  nextTick(() => {
    const element = promptInput.value;
    if (!element) {
      return;
    }
    element.style.height = "auto";
    element.style.overflowY = "hidden";
    const nextHeight = Math.min(element.scrollHeight, 150);
    element.style.height = `${nextHeight}px`;
    element.style.overflowY = element.scrollHeight > nextHeight ? "auto" : "hidden";
  });
}

function updatePrompt(event) {
  chat.setPrompt(event.target.value);
  resizeInput();
}

function submit() {
  if (sendBlocked.value) {
    sessions.setStatus("上下文压缩中，暂不可发送消息");
    return;
  }
  chat.submitPrompt();
  resizeInput();
}

function handleKeydown(event) {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    submit();
  }
}

function saveFinanceRetrievalStrategy() {
  sessions.saveSessions();
}

function savePaperRetrievalStrategy() {
  sessions.saveSessions();
}
</script>

<template>
  <form class="composer" id="chatForm" @submit.prevent="submit">
    <div class="input-wrap">
      <div class="input-area">
        <div class="attachment-preview" id="attachmentPreview" :hidden="!chat.attachments.length">
          <div v-for="file in chat.attachments" :key="file.index" class="attachment-item">
            <AppIcon name="paperclip" :size="13" />
            <span>{{ file.name }}</span>
            <button type="button" class="attachment-remove" title="移除附件" aria-label="移除附件" @click="chat.removeFile(file.index)">
              <AppIcon name="x" :size="13" />
            </button>
          </div>
        </div>
        <textarea
          id="promptInput"
          ref="promptInput"
          rows="1"
          :value="chat.prompt"
          :placeholder="sessions.placeholder"
          autocomplete="off"
          @input="updatePrompt"
          @keydown="handleKeydown"
        ></textarea>
      </div>
      <div class="input-actions">
        <label class="attach-button" id="attachButton" title="添加附件" aria-label="添加附件">
          <input type="file" id="fileInput" multiple hidden @change="chat.addFiles($event.target.files); $event.target.value = ''">
          <AppIcon name="paperclip" :size="19" />
        </label>
        <label class="model-picker composer-model-picker" for="modelSelect" title="选择模型">
          <FloatingSelect
            id="modelSelect"
            :model-value="models.activeModelId"
            :options="modelOptions"
            title="选择模型"
            @change="models.setActiveModel"
          />
        </label>
        <button
          class="send-button"
          id="sendButton"
          :class="{ 'is-running': chat.running }"
          :type="chat.running ? 'button' : 'submit'"
          :aria-label="chat.running ? '停止' : '发送'"
          :title="sendTitle"
          :disabled="sendBlocked"
          @click="chat.running ? chat.stop() : null"
        >
          <span id="sendIcon" aria-hidden="true">
            <AppIcon v-if="!chat.running" name="sendHorizontal" :size="19" />
            <AppIcon v-else name="square" :size="16" :stroke-width="2.4" />
          </span>
        </button>
      </div>
      <div class="runtime-options" id="runtimeOptions">
        <label title="GPT 类模型通常可直接使用 reasoning_effort；不选择则使用模型配置默认值。">
          <span>思考强度</span>
          <FloatingSelect id="runtimeReasoningEffort" v-model="runtimeReasoningEffort" :options="reasoningOptions" />
        </label>
        <label title="DeepSeek 兼容接口通常需要开启 thinking 后才会返回 reasoning_content。">
          <span>思考模式</span>
          <FloatingSelect id="runtimeThinkingType" v-model="runtimeThinkingType" :options="thinkingOptions" />
        </label>
        <label v-if="activeSession" id="financeRetrievalStrategyWrap" :hidden="sessions.mode !== 'finance'" title="金融问答召回策略。父子策略会回填父文本用于 rerank 和回答。">
          <span>召回策略</span>
          <FloatingSelect
            id="financeRetrievalStrategy"
            v-model="activeSession.financeRetrievalStrategy"
            :options="financeRetrievalOptions"
            @change="saveFinanceRetrievalStrategy"
          />
        </label>
        <label v-if="activeSession" id="paperRetrievalStrategyWrap" :hidden="sessions.mode !== 'paper'" title="论文问答召回策略。父子策略会用命中段落所在章节补充上下文。">
          <span>召回策略</span>
          <FloatingSelect
            id="paperRetrievalStrategy"
            v-model="activeSession.paperRetrievalStrategy"
            :options="financeRetrievalOptions"
            @change="savePaperRetrievalStrategy"
          />
        </label>
      </div>
    </div>
  </form>
</template>
