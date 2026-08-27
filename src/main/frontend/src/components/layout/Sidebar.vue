<script setup>
import { computed } from "vue";
import AppIcon from "../common/AppIcon.vue";
import { useKnowledgeStore } from "../../stores/knowledge";
import { useSessionStore } from "../../stores/session";

const sessions = useSessionStore();
const knowledge = useKnowledgeStore();

const history = computed(() => sessions.sessionsForMode);

function sessionPreview(session) {
  const firstPrompt = (session.messages || [])
    .find((message) => message?.role === "user" && String(message.text || "").trim())
    ?.text;
  const preview = String(firstPrompt || "").trim().replace(/\s+/g, " ");
  return preview || "新会话";
}

</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-mark" aria-hidden="true">K</div>
      <div class="brand-title">Kecore AI</div>
    </div>

    <button class="new-chat" id="newChatButton" type="button" title="新建对话" @click="sessions.requestNewSession">
      <span><AppIcon name="plus" :size="16" />新建</span>
    </button>

    <div class="mode-switch" role="tablist" aria-label="对话模式">
      <button
        class="mode-button"
        id="chatModeButton"
        type="button"
        data-mode="chat"
        role="tab"
        :data-active="sessions.mode === 'chat' ? 'true' : null"
        :aria-selected="sessions.mode === 'chat' ? 'true' : 'false'"
        @click="sessions.changeMode('chat')"
      >
        <AppIcon name="messageCircle" :size="15" />
        <span>对话</span>
      </button>
      <button
        class="mode-button"
        id="financeModeButton"
        type="button"
        data-mode="finance"
        role="tab"
        hidden
        :data-active="sessions.mode === 'finance' ? 'true' : null"
        :aria-selected="sessions.mode === 'finance' ? 'true' : 'false'"
        @click="sessions.changeMode('finance')"
      >
        <AppIcon name="database" :size="15" />
        <span>金融问答</span>
      </button>
      <button class="mode-button" id="gameModeButton" type="button" data-mode="game" role="tab" hidden>哄哄模拟器</button>
      <button
        class="mode-button"
        id="paperModeButton"
        type="button"
        data-mode="paper"
        role="tab"
        :data-active="sessions.mode === 'paper' ? 'true' : null"
        :aria-selected="sessions.mode === 'paper' ? 'true' : 'false'"
        @click="sessions.changeMode('paper')"
      >
        <AppIcon name="fileText" :size="15" />
        <span>论文问答</span>
      </button>
      <button
        class="mode-button"
        id="agentModeButton"
        type="button"
        data-mode="agent"
        role="tab"
        :data-active="sessions.mode === 'agent' ? 'true' : null"
        :aria-selected="sessions.mode === 'agent' ? 'true' : 'false'"
        @click="sessions.changeMode('agent')"
      >
        <AppIcon name="bot" :size="15" />
        <span>Agent</span>
      </button>
    </div>

    <div class="history-label">最近对话</div>
    <div class="history-list" id="historyList">
      <div v-for="session in history" :key="session.id" class="history-row">
        <button
          class="history-item"
          type="button"
          :title="sessionPreview(session)"
          :data-active="sessions.activeSession?.id === session.id ? 'true' : null"
          @click="sessions.switchSession(session.id)"
        >
          <span>{{ sessionPreview(session) }}</span>
        </button>
        <button
          v-if="session.mode === 'agent'"
          class="knowledge-session-button"
          type="button"
          title="知识库管理"
          aria-label="知识库管理"
          @click.stop="knowledge.openForSession(session.id)"
        >
          <AppIcon name="database" :size="15" />
        </button>
        <button
          class="session-delete-button"
          type="button"
          title="删除会话"
          aria-label="删除会话"
          @click.stop="sessions.deleteSession(session.id)"
        >
          <AppIcon name="trash2" :size="15" />
        </button>
      </div>
    </div>
  </aside>
</template>
