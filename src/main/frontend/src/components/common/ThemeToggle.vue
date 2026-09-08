<script setup>
import { computed, ref } from "vue";
import AppIcon from "./AppIcon.vue";

const THEME_KEY = "kecore-theme";
const theme = ref(document.documentElement.dataset.theme === "dark" ? "dark" : "light");
const isDark = computed(() => theme.value === "dark");
const toggleLabel = computed(() => isDark.value ? "切换到阳光模式" : "切换到黑夜模式");

function setTheme(nextTheme) {
  theme.value = nextTheme;
  document.documentElement.dataset.theme = nextTheme;
  document.documentElement.style.colorScheme = nextTheme;
  localStorage.setItem(THEME_KEY, nextTheme);
}

function toggleTheme() {
  setTheme(isDark.value ? "light" : "dark");
}
</script>

<template>
  <div class="appearance-footer">
    <div class="appearance-caption"><span class="appearance-dot" aria-hidden="true"></span><div>让想法，向前一步<small>THINK · CREATE · EXPLORE</small></div></div>
  <button
    class="theme-toggle"
    type="button"
    :class="{ 'is-dark': isDark }"
    :title="toggleLabel"
    :aria-label="toggleLabel"
    :aria-pressed="isDark ? 'true' : 'false'"
    @click="toggleTheme"
  >
    <span class="theme-toggle-thumb" aria-hidden="true"></span>
    <span class="theme-toggle-icon theme-toggle-sun" aria-hidden="true">
      <AppIcon name="sun" :size="17" :stroke-width="1.9" />
    </span>
    <span class="theme-toggle-icon theme-toggle-moon" aria-hidden="true">
      <AppIcon name="moon" :size="16" :stroke-width="1.9" />
    </span>
  </button>
  </div>
</template>
