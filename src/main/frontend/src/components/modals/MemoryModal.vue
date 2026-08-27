<script setup>
import { watch } from "vue";
import AppIcon from "../common/AppIcon.vue";
import FloatingSelect from "../common/FloatingSelect.vue";
import { useWorkspaceStore } from "../../stores/workspace";

const workspace = useWorkspaceStore();
const memoryTypeOptions = [
  { value: "user", label: "user" },
  { value: "feedback", label: "feedback" },
  { value: "project", label: "project" },
  { value: "reference", label: "reference" }
];

watch(() => workspace.selectedMemoryFileId, () => workspace.loadSelectedMemoryFile());
</script>

<template>
  <div class="memory-modal" id="memoryModal" :hidden="!workspace.memoryOpen" @click.self="workspace.closeMemory">
    <div class="memory-dialog" role="dialog" aria-modal="true" aria-labelledby="memoryTitle">
      <div class="memory-header">
        <div>
          <h2 id="memoryTitle">长期记忆</h2>
          <span id="memoryDirectory">{{ workspace.workingDirectory }}</span>
        </div>
        <button class="knowledge-close" id="memoryCloseButton" type="button" aria-label="关闭" @click="workspace.closeMemory">
          <AppIcon name="x" :size="18" />
        </button>
      </div>
      <div class="memory-layout">
        <section class="memory-create">
          <div class="memory-section-title">Add memory</div>
          <div class="memory-create-grid">
            <label>
              <span>Type</span>
              <FloatingSelect id="memoryTypeSelect" v-model="workspace.memoryCreateForm.type" :options="memoryTypeOptions" />
            </label>
            <label>
              <span>Name</span>
              <input id="memoryNameInput" v-model="workspace.memoryCreateForm.name" type="text" autocomplete="off" placeholder="prefer_tabs">
            </label>
          </div>
          <label>
            <span>Description</span>
            <input id="memoryDescriptionInput" v-model="workspace.memoryCreateForm.description" type="text" autocomplete="off" placeholder="User prefers tabs for indentation">
          </label>
          <label>
            <span>Opportunity</span>
            <input id="memoryOpportunityInput" v-model="workspace.memoryCreateForm.opportunity" type="text" autocomplete="off" placeholder="每次会话 / 代码编辑前 / 用户确认后">
          </label>
          <label>
            <span>Content</span>
            <textarea id="memoryContentInput" v-model="workspace.memoryCreateForm.content" spellcheck="false" placeholder="The user explicitly prefers tabs over spaces when editing source files."></textarea>
          </label>
          <div class="memory-create-actions">
            <button id="memoryCreateButton" class="icon-text-button" type="button" @click="workspace.createMemory">
              <AppIcon name="save" :size="15" />
              <span>Save memory</span>
            </button>
          </div>
        </section>
        <section class="memory-edit-panel">
          <div class="memory-toolbar">
            <div class="memory-toolbar-heading">
              <div class="memory-section-title">Edit memory</div>
              <span id="memorySelectedPath">{{ workspace.selectedMemoryFile?.path || 'No memory selected' }}</span>
            </div>
            <FloatingSelect
              id="memoryFileSelect"
              v-model="workspace.selectedMemoryFileId"
              :options="workspace.memoryFileOptions"
              :disabled="!workspace.memoryFiles.length"
              placeholder="No memory selected"
            />
            <div class="memory-toolbar-actions">
              <button id="memoryReloadButton" class="icon-text-button" type="button" @click="workspace.loadMemoryFiles">
                <AppIcon name="refreshCw" :size="15" />
                <span>刷新</span>
              </button>
              <button id="memorySaveButton" class="icon-text-button" type="button" @click="workspace.saveSelectedMemoryFile">
                <AppIcon name="save" :size="15" />
                <span>保存</span>
              </button>
            </div>
          </div>
          <div class="memory-edit-layout">
            <nav class="memory-file-list" id="memoryFileList" aria-label="Memory files">
              <section v-for="group in workspace.memoryFileGroups" :key="group.group" class="memory-file-group">
                <div class="memory-file-group-title">{{ group.group }}</div>
                <button
                  v-for="file in group.files"
                  :key="file.id"
                  class="memory-file-item"
                  type="button"
                  :aria-selected="workspace.selectedMemoryFileId === file.id ? 'true' : 'false'"
                  :title="file.path || file.label || file.id"
                  @click="workspace.selectedMemoryFileId = file.id"
                >
                  <span class="memory-file-name">{{ file.name }}</span>
                  <span class="memory-file-path">{{ file.exists ? file.path : `${file.path} (new)` }}</span>
                </button>
              </section>
            </nav>
            <div class="memory-editor-panel">
              <div class="memory-editor-title" id="memorySelectedTitle">{{ workspace.selectedMemoryFile?.name || 'No memory selected' }}</div>
              <textarea id="memoryEditor" v-model="workspace.memoryEditor" spellcheck="false" :disabled="!workspace.selectedMemoryFile"></textarea>
            </div>
          </div>
        </section>
      </div>
      <div class="knowledge-status" id="memoryStatus">{{ workspace.memoryStatus }}</div>
    </div>
  </div>
</template>
