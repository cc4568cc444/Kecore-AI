<script setup>
import AppIcon from "../common/AppIcon.vue";
import FloatingSelect from "../common/FloatingSelect.vue";
import { useModelStore } from "../../stores/model";

const models = useModelStore();
const reasoningOptions = [
  { value: "", label: "不传", buttonLabel: "不传", title: "不向模型请求传递 reasoning_effort" },
  { value: "low", label: "low" },
  { value: "medium", label: "medium" },
  { value: "high", label: "high" },
  { value: "xhigh", label: "xhigh" },
  { value: "max", label: "max" }
];
const thinkingOptions = [
  { value: "", label: "不传", buttonLabel: "不传", title: "不向模型请求传递 thinking 参数" },
  { value: "enabled", label: "开启" },
  { value: "disabled", label: "关闭" }
];
</script>

<template>
  <div class="model-modal" id="modelModal" :hidden="!models.modalOpen" @click.self="models.closeModal">
    <div class="model-dialog" role="dialog" aria-modal="true" aria-labelledby="modelTitle">
      <div class="model-header">
        <div>
          <h2 id="modelTitle">模型管理</h2>
          <span id="modelStatus">{{ models.status }}</span>
        </div>
        <button class="knowledge-close" id="modelCloseButton" type="button" aria-label="关闭" @click="models.closeModal">
          <AppIcon name="x" :size="18" />
        </button>
      </div>
      <div class="model-layout">
        <aside class="model-list-panel">
          <div class="model-list-toolbar">
            <button id="modelNewButton" class="icon-text-button" type="button" @click="models.resetForm">
              <AppIcon name="plus" :size="15" />
              <span>新增模型</span>
            </button>
            <button id="modelReloadButton" class="icon-text-button" type="button" @click="models.loadModels">
              <AppIcon name="refreshCw" :size="15" />
              <span>刷新</span>
            </button>
          </div>
          <nav class="model-list" id="modelList" aria-label="模型列表">
            <button
              v-for="model in models.models"
              :key="model.id"
              class="model-item"
              type="button"
              :aria-selected="models.selectedModelId === model.id ? 'true' : 'false'"
              @click="models.selectModel(model.id)"
            >
              <strong>{{ model.name || model.model }}</strong>
              <span>{{ model.provider || 'OpenAI Compatible' }} · {{ model.model }}</span>
              <em>{{ model.enabled === false ? '已禁用' : '已启用' }}</em>
            </button>
          </nav>
        </aside>
        <form class="model-form" id="modelForm" @submit.prevent="models.saveModel">
          <input id="modelIdInput" v-model="models.form.id" type="hidden">
          <div class="model-form-grid">
            <label>
              <span>名称</span>
              <input id="modelNameInput" v-model="models.form.name" type="text" required placeholder="DeepSeek V4">
            </label>
            <label>
              <span>供应商</span>
              <input id="modelProviderInput" v-model="models.form.provider" type="text" placeholder="OpenAI Compatible">
            </label>
            <label class="wide">
              <span>Base URL</span>
              <input id="modelBaseUrlInput" v-model="models.form.baseUrl" type="url" required placeholder="https://api.deepseek.com">
            </label>
            <label>
              <span>Completions Path</span>
              <input id="modelPathInput" v-model="models.form.completionsPath" type="text" placeholder="/v1/chat/completions">
            </label>
            <label>
              <span>模型名</span>
              <input id="modelNameValueInput" v-model="models.form.model" type="text" required placeholder="deepseek-v4-pro">
            </label>
            <label>
              <span>Temperature</span>
              <input id="modelTemperatureInput" v-model="models.form.temperature" type="number" min="0" max="2" step="0.1" placeholder="0.4">
            </label>
            <label>
              <span>思考强度</span>
              <FloatingSelect id="modelReasoningEffortInput" v-model="models.form.reasoningEffort" :options="reasoningOptions" />
            </label>
            <label>
              <span>思考模式</span>
              <FloatingSelect id="modelThinkingTypeInput" v-model="models.form.thinkingType" :options="thinkingOptions" />
            </label>
            <label class="wide">
              <span>API Key</span>
              <input id="modelApiKeyInput" v-model="models.form.apiKey" type="password" autocomplete="new-password" placeholder="留空表示保留已保存 Key">
            </label>
            <label class="wide">
              <span>自定义 extra_body JSON</span>
              <textarea id="modelExtraBodyInput" v-model="models.form.extraBody" rows="4" placeholder='{"thinking":{"type":"enabled"}}'></textarea>
            </label>
          </div>
          <label class="model-enabled">
            <input id="modelEnabledInput" v-model="models.form.enabled" type="checkbox">
            <span>启用该模型</span>
          </label>
          <div class="model-form-actions">
            <button id="modelDeleteButton" class="danger icon-text-button" type="button" :disabled="!models.selectedModelId || models.busy" @click="models.deleteSelectedModel">
              <AppIcon name="trash2" :size="15" />
              <span>删除</span>
            </button>
            <button id="modelSaveButton" class="icon-text-button" type="submit" :disabled="models.busy">
              <AppIcon name="save" :size="15" />
              <span>保存模型</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
