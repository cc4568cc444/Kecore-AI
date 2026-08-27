<script setup>
import AppShell from "./components/layout/AppShell.vue";
import WorkspaceModal from "./components/modals/WorkspaceModal.vue";
import AgentInteractionModal from "./components/modals/AgentInteractionModal.vue";
import ContextDrawer from "./components/modals/ContextDrawer.vue";
import MemoryModal from "./components/modals/MemoryModal.vue";
import SkillsModal from "./components/modals/SkillsModal.vue";
import KnowledgeModal from "./components/modals/KnowledgeModal.vue";
import ImagePreviewModal from "./components/modals/ImagePreviewModal.vue";
import ModelModal from "./components/modals/ModelModal.vue";
import MigrationModal from "./components/modals/MigrationModal.vue";
</script>

<template>
  <AppShell />
  <WorkspaceModal />
  <AgentInteractionModal />
  <ContextDrawer />
  <MemoryModal />
  <SkillsModal />
  <KnowledgeModal />
  <ImagePreviewModal />
  <ModelModal />
  <MigrationModal />
</template>
