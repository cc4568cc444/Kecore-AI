<script setup>
import { computed } from "vue";
import { useSessionStore } from "../../stores/session";

const sessions = useSessionStore();
const mood = computed(() => sessions.activeSession?.mood ?? -60);
const progress = computed(() => `${Math.max(0, Math.min(100, mood.value + 100))}%`);
</script>

<template>
  <section class="game-score" id="gameScore" :hidden="sessions.mode !== 'game'">
    <div class="score-copy">
      <span class="score-label">心情值</span>
      <strong id="moodValue">{{ mood }}</strong>
    </div>
    <div class="score-track"><span id="moodProgress" :style="{ width: progress }"></span></div>
    <span class="game-state" id="moodState">{{ sessions.activeSession?.gameState || '生气中' }}</span>
  </section>
</template>
