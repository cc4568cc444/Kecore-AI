<script setup>
import { computed, ref, watch } from "vue";
import { useChatStore } from "../../stores/chat";
import { useWorkspaceStore } from "../../stores/workspace";

const chat = useChatStore();
const workspace = useWorkspaceStore();
const textValue = ref("");
const interaction = computed(() => chat.agentInteraction);
const submitDisabled = computed(() => chat.running || workspace.contextCompacting);

watch(interaction, () => {
  textValue.value = "";
});

const choices = computed(() => {
  const value = interaction.value;
  if (!value) {
    return [];
  }
  if (Array.isArray(value.choices) && value.choices.length) {
    return value.choices;
  }
  if (value.type === "choice" || value.kind === "choice") {
    return ["继续", "停止"];
  }
  return [];
});

const wantsText = computed(() => !choices.value.length);

function submit(value = textValue.value) {
  if (!interaction.value?.runId || submitDisabled.value) {
    return;
  }
  chat.submitAgentInteraction(interaction.value.runId, value);
}
</script>

<template>
  <div class="agent-interaction-modal" id="agentInteractionModal" :hidden="!interaction" @click.self="chat.closeInteraction">
    <form class="agent-interaction-dialog" id="agentInteractionForm" role="dialog" aria-modal="true" aria-labelledby="agentInteractionTitle" @submit.prevent="submit()">
      <div class="agent-interaction-header">
        <h2 id="agentInteractionTitle">{{ interaction?.title || '需要你的确认' }}</h2>
        <p id="agentInteractionDescription">{{ interaction?.description || interaction?.message || '请提供后续处理方式。' }}</p>
      </div>
      <div class="agent-choice-list" id="agentChoiceList">
        <button v-for="choice in choices" :key="choice" type="button" :disabled="submitDisabled" @click="submit(choice)">{{ choice }}</button>
      </div>
      <div class="agent-text-input" id="agentTextInputWrap" :hidden="!wantsText">
        <input id="agentTextInput" v-model="textValue" type="text" autocomplete="off">
        <button type="submit" :disabled="submitDisabled">提交</button>
      </div>
    </form>
  </div>
</template>
