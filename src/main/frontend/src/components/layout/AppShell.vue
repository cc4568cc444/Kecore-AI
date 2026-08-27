<script setup>
import Sidebar from "./Sidebar.vue";
import Topbar from "./Topbar.vue";
import GameScore from "../chat/GameScore.vue";
import MessagesPanel from "../chat/MessagesPanel.vue";
import Composer from "../chat/Composer.vue";
import ThemeToggle from "../common/ThemeToggle.vue";
import { useSessionStore } from "../../stores/session";

const sessions = useSessionStore();
</script>

<template>
  <div class="app-shell">
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
