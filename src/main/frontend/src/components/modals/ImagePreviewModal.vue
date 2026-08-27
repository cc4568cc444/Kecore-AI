<script setup>
import AppIcon from "../common/AppIcon.vue";
import { useChatStore } from "../../stores/chat";

const chat = useChatStore();
</script>

<template>
  <div class="image-preview-modal" id="imagePreviewModal" :hidden="!chat.imagePreviewUrl" @click.self="chat.closeImagePreview">
    <div class="image-preview-dialog" role="dialog" aria-modal="true" aria-labelledby="imagePreviewTitle">
      <div class="image-preview-header">
        <h2 id="imagePreviewTitle">图片预览</h2>
        <div class="image-preview-actions">
          <a id="imagePreviewOpen" class="icon-text-button" :href="chat.imagePreviewUrl" target="_blank" rel="noopener noreferrer">
            <AppIcon name="folderOpen" :size="15" />
            <span>新标签页打开</span>
          </a>
          <button class="knowledge-close" id="imagePreviewCloseButton" type="button" aria-label="关闭" @click="chat.closeImagePreview">
            <AppIcon name="x" :size="18" />
          </button>
        </div>
      </div>
      <div class="image-preview-stage">
        <img id="imagePreviewImage" :src="chat.imagePreviewUrl" alt="图片预览">
      </div>
    </div>
  </div>
</template>
