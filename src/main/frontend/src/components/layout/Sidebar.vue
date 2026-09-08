<script setup>
import { computed, ref } from "vue";
import { useShellStore } from "../../stores/shell";
import { useModelStore } from "../../stores/model";
import { useMigrationStore } from "../../stores/migration";
import AppIcon from "../common/AppIcon.vue";
import { useKnowledgeStore } from "../../stores/knowledge";
import { useSessionStore } from "../../stores/session";

const sessions = useSessionStore();
const knowledge = useKnowledgeStore();

const shell = useShellStore();
const models = useModelStore();
const migration = useMigrationStore();
const search = ref("");
const history = computed(() => sessions.sessionsForMode.filter(item => sessionPreview(item).toLowerCase().includes(search.value.trim().toLowerCase())));

function sessionPreview(session) {
  const firstPrompt = (session.messages || [])
    .find((message) => message?.role === "user" && String(message.text || "").trim())
    ?.text;
  const preview = String(firstPrompt || "").trim().replace(/\s+/g, " ");
  return preview || "新会话";
}

</script>

<template>
  <aside v-show="shell.sidebarOpen" class="sidebar" id="appSidebar" aria-label="对话导航">
    <div class="brand">
      <div class="brand-mark" aria-hidden="true">K</div>
      <div class="brand-title">Kecore AI</div>
    </div>

    <button class="new-chat" id="newChatButton" type="button" title="新建对话" @click="sessions.requestNewSession(); shell.closeMobileSidebar()">
      <span><AppIcon name="plus" :size="16" />新对话</span>
    </button>

    <div class="nav-caption">对话模式</div>
    <div class="mode-switch" role="tablist" aria-label="对话模式">
      <button
        class="mode-button"
        id="chatModeButton"
        type="button"
        data-mode="chat"
        role="tab"
        :data-active="sessions.mode === 'chat' ? 'true' : null"
        :aria-selected="sessions.mode === 'chat' ? 'true' : 'false'"
        @click="sessions.changeMode('chat'); shell.closeMobileSidebar()"
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
        :data-active="sessions.mode === 'finance' ? 'true' : null"
        :aria-selected="sessions.mode === 'finance' ? 'true' : 'false'"
        @click="sessions.changeMode('finance'); shell.closeMobileSidebar()"
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
        hidden
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
        @click="sessions.changeMode('agent'); shell.closeMobileSidebar()"
      >
        <AppIcon name="bot" :size="15" />
        <span>Agent</span>
      </button>
    </div>

    <label class="history-search"><AppIcon name="search" :size="16" /><input v-model="search" placeholder="搜索对话" aria-label="搜索对话" /></label>
    <div class="history-label">最近对话</div>
    <div class="history-list" id="historyList">
      <p v-if="!history.length" class="history-empty">{{ search ? '没有找到相关对话' : '你的对话会显示在这里' }}</p>
      <div v-for="session in history" :key="session.id" class="history-row">
        <button
          class="history-item"
          type="button"
          :title="sessionPreview(session)"
          :data-active="sessions.activeSession?.id === session.id ? 'true' : null"
          @click="sessions.switchSession(session.id); shell.closeMobileSidebar()"
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
    <div class="sidebar-utilities">
      <button class="ghost-button icon-text-button" id="modelManageButton" type="button" @click="models.openModal(); shell.closeMobileSidebar()"><AppIcon name="settings" :size="16" />模型管理</button>
      <button class="ghost-button icon-text-button" id="vueMigrationButton" type="button" @click="migration.show(); shell.closeMobileSidebar()"><AppIcon name="download" :size="16" />数据迁移</button>
    </div>
  </aside>
</template>
