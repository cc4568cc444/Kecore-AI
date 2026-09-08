<script setup>
import { useShellStore } from "../../stores/shell";
import Sidebar from "./Sidebar.vue";
import Topbar from "./Topbar.vue";
import GameScore from "../chat/GameScore.vue";
import MessagesPanel from "../chat/MessagesPanel.vue";
import Composer from "../chat/Composer.vue";
import ThemeToggle from "../common/ThemeToggle.vue";
import { useSessionStore } from "../../stores/session";

const shell = useShellStore();
const sessions = useSessionStore();
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': !shell.sidebarOpen }" @keydown.esc="shell.sidebarOpen = false">
    <button v-if="shell.sidebarOpen" class="sidebar-backdrop" type="button" aria-label="关闭侧栏" @click="shell.sidebarOpen = false"></button>
    <Sidebar />

    <main class="chat-panel" :class="{ 'is-empty': !sessions.hasMessages }">
      <Topbar />
      <GameScore />
      <MessagesPanel />
      <Composer />
    </main>
    <ThemeToggle />
  </div>
</template>
