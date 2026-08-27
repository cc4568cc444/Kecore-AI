<script setup>
import AppIcon from "../common/AppIcon.vue";
import { useKnowledgeStore } from "../../stores/knowledge";

const knowledge = useKnowledgeStore();
</script>

<template>
  <div class="knowledge-modal" id="knowledgeModal" :hidden="!knowledge.open" @click.self="knowledge.close">
    <div class="knowledge-dialog" role="dialog" aria-modal="true" aria-labelledby="knowledgeTitle">
      <div class="knowledge-header">
        <div>
          <h2 id="knowledgeTitle">知识库管理</h2>
          <span id="knowledgeSessionTitle">{{ knowledge.sessionTitle }}</span>
        </div>
        <button class="knowledge-close" id="knowledgeCloseButton" type="button" aria-label="关闭" @click="knowledge.close">
          <AppIcon name="x" :size="18" />
        </button>
      </div>
      <div class="knowledge-upload">
        <input id="knowledgeFileInput" type="file" accept="application/pdf,.pdf" @change="knowledge.upload($event.target.files?.[0]); $event.target.value = ''">
        <button id="knowledgeUploadButton" class="icon-text-button" type="button" :disabled="knowledge.busy" @click="knowledge.loadDocuments">
          <AppIcon name="refreshCw" :size="15" />
          <span>刷新</span>
        </button>
      </div>
      <div class="knowledge-status" id="knowledgeStatus">{{ knowledge.status }}</div>
      <div class="knowledge-list" id="knowledgeList">
        <article v-for="document in knowledge.documents" :key="document.id || document.documentId || document.filename" class="knowledge-item">
          <div>
            <strong>{{ document.filename || document.name || document.title || 'PDF 文档' }}</strong>
            <span>{{ document.createdAt || document.uploadedAt || '' }}</span>
          </div>
          <button class="knowledge-delete icon-text-button" type="button" @click="knowledge.deleteDocument(document.id || document.documentId)">
            <AppIcon name="trash2" :size="15" />
            <span>删除</span>
          </button>
        </article>
      </div>
    </div>
  </div>
</template>
