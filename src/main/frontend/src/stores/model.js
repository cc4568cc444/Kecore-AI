import { defineStore } from "pinia";
import { api } from "../services/api";
import { MODEL_KEY } from "../services/config";
import { normalizeReasoningEffort, normalizeThinkingType } from "../utils/session";
import { useSessionStore } from "./session";

function blankModel() {
  return {
    id: "",
    name: "",
    provider: "OpenAI Compatible",
    baseUrl: "",
    completionsPath: "/v1/chat/completions",
    model: "",
    temperature: "",
    reasoningEffort: "",
    thinkingType: "",
    apiKey: "",
    extraBody: "",
    enabled: true
  };
}

export const useModelStore = defineStore("model", {
  state: () => ({
    models: [],
    activeModelId: localStorage.getItem(MODEL_KEY) || "",
    selectedModelId: "",
    modalOpen: false,
    status: "配置 OpenAI 兼容模型接口，并在顶部选择当前模型。",
    form: blankModel(),
    runtimeReasoningEffort: "",
    runtimeThinkingType: "",
    busy: false
  }),
  getters: {
    enabledModels: (state) => state.models.filter((model) => model.enabled !== false),
    activeModel(state) {
      return state.models.find((model) => model.id === state.activeModelId) || null;
    },
    selectedModel(state) {
      return state.models.find((model) => model.id === state.selectedModelId) || null;
    },
    currentModelId: (state) => state.activeModelId || "",
    modelOptions(state) {
      return state.enabledModels.map((model) => ({
          id: model.id,
          label: `${model.name || model.model} · ${model.model || ""}`,
          shortLabel: model.model || model.name || "未命名模型"
        }))
    }
  },
  actions: {
    async loadModels() {
      try {
        this.models = await api.listModels();
      } catch (error) {
        console.warn("加载模型配置失败:", error);
        this.models = [];
      }

      const enabledModels = this.models.filter(
        (model) => model.enabled !== false
      );

      const activeModelStillExists = enabledModels.some(
        (model) => model.id === this.activeModelId
      );

      if (!activeModelStillExists) {
        this.activeModelId = enabledModels[0]?.id || "";
        localStorage.setItem(MODEL_KEY, this.activeModelId);
      }

      if (
        !this.selectedModelId ||
        !this.models.some((model) => model.id === this.selectedModelId)
      ) {
        this.selectedModelId = this.models[0]?.id || "";
      }

      this.loadSelectedModel();
    },
    setActiveModel(modelId) {
      this.activeModelId = modelId || "";
      localStorage.setItem(MODEL_KEY, this.activeModelId);
    },
    runtimeOptionsPayload() {
      const model = this.activeModel;
      const session = useSessionStore().activeSession;
      const reasoningEffort = normalizeReasoningEffort(
        session?.runtimeReasoningEffort || this.runtimeReasoningEffort || model?.reasoningEffort
      );
      const thinkingType = normalizeThinkingType(
        session?.runtimeThinkingType || this.runtimeThinkingType || model?.thinkingType
      );
      return {
        reasoningEffort,
        thinkingType,
        extraBody: model?.extraBody || null
      };
    },
    openModal() {
      this.modalOpen = true;
      this.status = "配置 OpenAI 兼容模型接口，并在顶部选择当前模型。";
      if (!this.models.length) {
        this.loadModels();
      }
    },
    closeModal() {
      this.modalOpen = false;
    },
    resetForm() {
      this.selectedModelId = "";
      this.form = blankModel();
      this.status = "正在新增模型。";
    },
    selectModel(modelId) {
      this.selectedModelId = modelId || "";
      this.loadSelectedModel();
    },
    loadSelectedModel() {
      const model = this.selectedModel;
      if (!model) {
        this.form = blankModel();
        return;
      }
      this.form = {
        id: model.id || "",
        name: model.name || "",
        provider: model.provider || "OpenAI Compatible",
        baseUrl: model.baseUrl || "",
        completionsPath: model.completionsPath || "/v1/chat/completions",
        model: model.model || "",
        temperature: model.temperature ?? "",
        reasoningEffort: model.reasoningEffort || "",
        thinkingType: model.thinkingType || "",
        apiKey: "",
        extraBody: model.extraBody || "",
        enabled: model.enabled !== false
      };
    },
    payloadFromForm() {
      const extraBody = this.form.extraBody.trim();
      if (extraBody) {
        const parsed = JSON.parse(extraBody);
        if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
          throw new Error("extra_body 必须是 JSON 对象");
        }
      }
      return {
        id: this.form.id || null,
        name: this.form.name.trim(),
        provider: this.form.provider.trim() || "OpenAI Compatible",
        baseUrl: this.form.baseUrl.trim(),
        completionsPath: this.form.completionsPath.trim() || "/v1/chat/completions",
        model: this.form.model.trim(),
        temperature: this.form.temperature === "" ? null : Number(this.form.temperature),
        reasoningEffort: this.form.reasoningEffort || null,
        thinkingType: this.form.thinkingType || null,
        apiKey: this.form.apiKey,
        extraBody: extraBody || null,
        enabled: this.form.enabled !== false
      };
    },
    async saveModel() {
      this.busy = true;
      try {
        const saved = await api.saveModel(this.payloadFromForm());
        this.selectedModelId = saved.id;
        if (saved.enabled) {
          this.setActiveModel(saved.id);
        }
        await this.loadModels();
        this.status = "模型已保存";
      } finally {
        this.busy = false;
      }
    },
    async deleteSelectedModel() {
      if (!this.selectedModelId || this.selectedModelId === "default") {
        return;
      }
      this.busy = true;
      try {
        await api.deleteModel(this.selectedModelId);
        if (this.activeModelId === this.selectedModelId) {
          this.setActiveModel("");
        }
        this.selectedModelId = "";
        await this.loadModels();
        this.status = "模型已删除";
      } finally {
        this.busy = false;
      }
    }
  }
});
