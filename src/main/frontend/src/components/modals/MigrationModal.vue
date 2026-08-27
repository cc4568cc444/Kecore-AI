<script setup>
import { computed, ref } from "vue";
import { useMigrationStore } from "../../stores/migration";

const migration = useMigrationStore();
const fileInput = ref(null);

const previewModes = computed(() => {
  const modes = migration.importPreview?.modes || {};
  return Object.entries(modes)
    .map(([mode, count]) => `${mode}: ${count}`)
    .join(" · ");
});

function chooseFile() {
  fileInput.value?.click();
}

function handleFileChange(event) {
  const file = event.target.files?.[0];
  migration.readImportFile(file);
  event.target.value = "";
}
</script>

<template>
  <div v-if="migration.open" class="workspace-modal migration-modal" id="vueMigrationModal">
    <section class="workspace-dialog migration-dialog" role="dialog" aria-modal="true" aria-labelledby="migrationTitle">
      <div class="workspace-dialog-header">
        <h2 id="migrationTitle">数据导入导出</h2>
        <p>迁移会话和模型配置到当前前端入口。导出文件包含明文 API Key。</p>
      </div>

      <div class="migration-section">
        <div class="migration-section-heading">
          <strong>导出数据</strong>
          <span>当前本地 {{ migration.localSessionCount }} 个会话，将同时导出模型配置</span>
        </div>
        <button class="migration-primary" type="button" @click="migration.exportData">导出 JSON</button>
      </div>

      <div class="migration-section">
        <div class="migration-section-heading">
          <strong>导入数据</strong>
          <span>支持本功能导出的 JSON 文件</span>
        </div>
        <input ref="fileInput" type="file" accept="application/json,.json" hidden @change="handleFileChange">
        <button class="secondary" type="button" @click="chooseFile">选择文件</button>

        <div v-if="migration.importPreview" class="migration-preview">
          <div>会话数量：{{ migration.importPreview.count }}</div>
          <div>模型配置：{{ migration.importPreview.modelCount }} 个</div>
          <div v-if="previewModes">模式分布：{{ previewModes }}</div>
          <div v-if="migration.importPreview.exportedAt">导出时间：{{ migration.importPreview.exportedAt }}</div>
        </div>

        <div class="migration-radio-group">
          <label>
            <input v-model="migration.importMode" type="radio" value="merge">
            <span>合并到当前数据</span>
          </label>
          <label>
            <input v-model="migration.importMode" type="radio" value="replace">
            <span>覆盖当前数据</span>
          </label>
        </div>
      </div>

      <div class="migration-status">{{ migration.status }}</div>

      <div class="workspace-dialog-actions">
        <button class="secondary" type="button" :disabled="migration.busy" @click="migration.close">取消</button>
        <button type="button" :disabled="migration.busy || !migration.importPayload" @click="migration.importData">
          {{ migration.busy ? "导入中" : "确认导入" }}
        </button>
      </div>
    </section>
  </div>
</template>
