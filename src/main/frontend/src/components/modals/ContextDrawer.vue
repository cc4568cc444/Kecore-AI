<script setup>
import AppIcon from "../common/AppIcon.vue";
import { useWorkspaceStore } from "../../stores/workspace";

const workspace = useWorkspaceStore();
</script>

<template>
  <aside class="context-drawer" id="contextDrawer" :hidden="!workspace.contextDrawerOpen" aria-labelledby="contextDrawerTitle">
    <div class="context-drawer-backdrop" id="contextDrawerBackdrop" @click="workspace.closeContextDrawer"></div>
    <section class="context-drawer-panel" role="dialog" aria-modal="true">
      <div class="context-drawer-header">
        <div>
          <h2 id="contextDrawerTitle">模型上下文</h2>
          <p id="contextDrawerMeta">{{ workspace.contextStatus }}</p>
        </div>
        <button class="context-drawer-close" id="contextDrawerCloseButton" type="button" aria-label="关闭" @click="workspace.closeContextDrawer">
          <AppIcon name="x" :size="18" />
        </button>
      </div>
      <div class="context-drawer-tabs" role="tablist" aria-label="上下文内容">
        <button class="context-drawer-tab" :class="{ active: workspace.contextTab === 'messages' }" type="button" @click="workspace.contextTab = 'messages'">消息</button>
        <button class="context-drawer-tab" :class="{ active: workspace.contextTab === 'tools' }" type="button" @click="workspace.contextTab = 'tools'">工具</button>
      </div>
      <div class="context-drawer-body">
        <div class="context-drawer-section" id="contextMessagesPanel" :hidden="workspace.contextTab !== 'messages'">
          <article v-for="(message, index) in workspace.contextPreview?.messages || []" :key="index" class="context-preview-message" :class="message.role">
            <strong>{{ message.role }}</strong>
            <pre>{{ message.content || message.text || '' }}</pre>
          </article>
        </div>
        <div class="context-drawer-section" id="contextToolsPanel" :hidden="workspace.contextTab !== 'tools'">
          <article v-for="(tool, index) in workspace.contextPreview?.tools || []" :key="index" class="context-preview-tool">
            <strong>{{ tool.name || tool.toolName || `工具 ${index + 1}` }}</strong>
            <pre>{{ tool.arguments || tool.content || JSON.stringify(tool, null, 2) }}</pre>
          </article>
        </div>
      </div>
    </section>
  </aside>
</template>
