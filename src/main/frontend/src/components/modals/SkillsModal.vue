<script setup>
import { watch } from "vue";
import AppIcon from "../common/AppIcon.vue";
import { useWorkspaceStore } from "../../stores/workspace";

const workspace = useWorkspaceStore();

watch(() => workspace.selectedSkillCommand, () => {
  if (workspace.selectedSkillCommand) {
    workspace.loadSelectedSkill();
  }
});
</script>

<template>
  <div class="skills-modal" id="skillsModal" :hidden="!workspace.skillsOpen" @click.self="workspace.closeSkills">
    <div class="skills-dialog" role="dialog" aria-modal="true" aria-labelledby="skillsTitle">
      <div class="skills-header">
        <div>
          <h2 id="skillsTitle">Skills 管理</h2>
          <span id="skillsDirectory">{{ workspace.workingDirectory }}</span>
        </div>
        <button class="knowledge-close" id="skillsCloseButton" type="button" aria-label="关闭" @click="workspace.closeSkills">
          <AppIcon name="x" :size="18" />
        </button>
      </div>
      <div class="skills-layout">
        <aside class="skills-list-panel">
          <div class="skills-list-toolbar">
            <input id="skillsSearchInput" v-model="workspace.skillSearch" type="search" placeholder="搜索 skill">
            <button id="skillsReloadButton" class="icon-text-button" type="button" @click="workspace.loadSkills">
              <AppIcon name="refreshCw" :size="15" />
              <span>刷新</span>
            </button>
          </div>
          <nav class="skills-list" id="skillsList" aria-label="Skills">
            <button
              v-for="skill in workspace.filteredSkills"
              :key="skill.commandName"
              class="skill-item"
              type="button"
              :aria-selected="workspace.selectedSkillCommand === skill.commandName ? 'true' : 'false'"
              @click="workspace.selectedSkillCommand = skill.commandName"
            >
              <strong>/{{ skill.commandName }}</strong>
              <span>{{ skill.description || skill.path || '' }}</span>
            </button>
          </nav>
        </aside>
        <section class="skills-preview-panel">
          <div class="skills-preview-header">
            <div class="skills-preview-heading">
              <div class="memory-section-title" id="skillsSelectedTitle">{{ workspace.selectedSkillCommand ? `/${workspace.selectedSkillCommand}` : 'No skill selected' }}</div>
              <span id="skillsSelectedMeta">{{ workspace.selectedSkill?.path || '请选择一个 skill' }}</span>
            </div>
            <div class="skills-preview-actions">
              <button id="skillsNewButton" class="icon-text-button" type="button" @click="workspace.newSkillFile">
                <AppIcon name="filePlus" :size="15" />
                <span>新建</span>
              </button>
              <button id="skillsSaveButton" class="icon-text-button" type="button" @click="workspace.saveSelectedSkillFile">
                <AppIcon name="save" :size="15" />
                <span>保存</span>
              </button>
              <button id="skillsDeleteButton" class="icon-text-button" type="button" @click="workspace.deleteSelectedSkill">
                <AppIcon name="trash2" :size="15" />
                <span>删除</span>
              </button>
              <button id="skillsInsertButton" class="icon-text-button" type="button" @click="workspace.insertSkillInvocation">
                <AppIcon name="sendHorizontal" :size="15" />
                <span>插入调用</span>
              </button>
              <button id="skillsCopyButton" class="icon-text-button" type="button" @click="workspace.copySkillPreview">
                <AppIcon name="copy" :size="15" />
                <span>复制</span>
              </button>
            </div>
          </div>
          <div class="skills-arguments">
            <label for="skillArgumentsInput">Arguments</label>
            <div class="skills-argument-row">
              <input id="skillArgumentsInput" v-model="workspace.skillArguments" type="text" autocomplete="off" placeholder="可选参数，例如 123 或 staging">
              <button id="skillsRenderButton" class="icon-text-button" type="button" @click="workspace.renderSelectedSkill">
                <AppIcon name="refreshCw" :size="15" />
                <span>渲染预览</span>
              </button>
            </div>
          </div>
          <textarea id="skillsPreview" v-model="workspace.skillPreview" spellcheck="false"></textarea>
          <pre id="skillsRenderedPreview" :hidden="!workspace.skillRenderedPreview">{{ workspace.skillRenderedPreview }}</pre>
        </section>
      </div>
      <div class="knowledge-status" id="skillsStatus">{{ workspace.skillsStatus }}</div>
    </div>
  </div>
</template>
