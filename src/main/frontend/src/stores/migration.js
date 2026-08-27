import { defineStore } from "pinia";
import { api } from "../services/api";
import { ACTIVE_SESSION_KEY, MODE_KEY, MODEL_KEY, STORAGE_KEY } from "../services/config";
import { normalizeSession as normalizeAppSession } from "../utils/session";
import { useModelStore } from "./model";
import { useSessionStore } from "./session";

const EXPORT_VERSION = 1;

function readJsonStorage(key, fallback) {
  try {
    const rawValue = localStorage.getItem(key);
    return rawValue ? JSON.parse(rawValue) : fallback;
  } catch (error) {
    return fallback;
  }
}

function downloadJson(filename, payload) {
  const blob = new Blob([JSON.stringify(payload, null, 2)], {
    type: "application/json;charset=utf-8"
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function normalizeModels(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .filter((model) => model && typeof model === "object")
    .map((model) => ({
      id: String(model.id || "").trim(),
      name: String(model.name || model.model || "迁移模型"),
      provider: String(model.provider || "OpenAI Compatible"),
      baseUrl: String(model.baseUrl || ""),
      completionsPath: String(model.completionsPath || "/v1/chat/completions"),
      apiKey: String(model.apiKey || ""),
      model: String(model.model || ""),
      temperature: model.temperature === null || model.temperature === undefined || model.temperature === ""
        ? null
        : Number(model.temperature),
      reasoningEffort: model.reasoningEffort || null,
      thinkingType: model.thinkingType || null,
      extraBody: model.extraBody || null,
      enabled: model.enabled !== false
    }))
    .filter((model) => model.id && model.baseUrl && model.model);
}

function parseImportPayload(value) {
  const payload = typeof value === "string" ? JSON.parse(value) : value;
  const storageDump = payload?.localStorage || payload?.storage || payload;
  const data = payload?.data || payload;
  let sessions = Array.isArray(payload) ? payload : data?.sessions;
  let models = data?.models;
  let activeSessionIds = data?.activeSessionIds;
  let mode = data?.mode;
  let activeModelId = data?.activeModelId;

  if (!Array.isArray(sessions) && storageDump?.[STORAGE_KEY]) {
    sessions = typeof storageDump[STORAGE_KEY] === "string"
      ? JSON.parse(storageDump[STORAGE_KEY])
      : storageDump[STORAGE_KEY];
    activeSessionIds = storageDump[ACTIVE_SESSION_KEY]
      ? (typeof storageDump[ACTIVE_SESSION_KEY] === "string"
          ? JSON.parse(storageDump[ACTIVE_SESSION_KEY])
          : storageDump[ACTIVE_SESSION_KEY])
      : activeSessionIds;
    mode = storageDump[MODE_KEY] || mode;
    activeModelId = storageDump[MODEL_KEY] || activeModelId;
  }
  if (!Array.isArray(models) && Array.isArray(payload?.models)) {
    models = payload.models;
  }

  if (!Array.isArray(sessions)) {
    throw new Error("导入文件中没有找到 sessions 数组。");
  }

  return {
    exportedAt: payload?.exportedAt || "",
    source: payload?.source || "spring-ai",
    sessions,
    models: normalizeModels(models),
    activeSessionIds: activeSessionIds && typeof activeSessionIds === "object" ? activeSessionIds : {},
    mode: typeof mode === "string" ? mode : "chat",
    activeModelId: typeof activeModelId === "string" ? activeModelId : ""
  };
}

function mergeSessions(currentSessions, incomingSessions, strategy) {
  if (strategy === "replace") {
    return incomingSessions;
  }

  const sessionMap = new Map();
  for (const session of currentSessions) {
    sessionMap.set(session.id, session);
  }
  for (const session of incomingSessions) {
    sessionMap.set(session.id, session);
  }

  return [...sessionMap.values()].sort((a, b) => Number(b.updatedAt || 0) - Number(a.updatedAt || 0));
}

async function exportModelsFromServer() {
  return normalizeModels(await api.exportModels());
}

async function importModelsToServer(models) {
  if (!models.length) {
    return [];
  }
  return api.importModels(models);
}

export const useMigrationStore = defineStore("migration", {
  state: () => ({
    open: false,
    importPreview: null,
    importPayload: null,
    importMode: "merge",
    status: "可导出旧版本地数据，也可导入迁移文件。",
    busy: false,
    localSessionCount: 0
  }),
  actions: {
    readLocalSnapshot() {
      const sessions = readJsonStorage(STORAGE_KEY, []);
      return {
        sessions: Array.isArray(sessions) ? sessions : [],
        activeSessionIds: readJsonStorage(ACTIVE_SESSION_KEY, {}),
        mode: localStorage.getItem(MODE_KEY) || "chat",
        activeModelId: localStorage.getItem(MODEL_KEY) || ""
      };
    },
    refreshLocalSummary() {
      this.localSessionCount = this.readLocalSnapshot().sessions.length;
    },
    show() {
      this.refreshLocalSummary();
      this.open = true;
      this.status = "可导出旧版本地数据和模型配置。导出文件包含明文 API Key，请妥善保存。";
    },
    close() {
      if (this.busy) {
        return;
      }
      this.open = false;
    },
    async exportData() {
      const snapshot = this.readLocalSnapshot();
      const sessions = snapshot.sessions;
      let models = [];
      try {
        models = await exportModelsFromServer();
      } catch (error) {
        this.status = "模型配置导出失败，仅导出会话数据。请确认后端已启动。";
      }

      const payload = {
        type: "spring-ai-frontend-migration",
        version: EXPORT_VERSION,
        exportedAt: new Date().toISOString(),
        source: {
          origin: window.location.origin,
          pathname: window.location.pathname
        },
        data: {
          sessions,
          models,
          activeSessionIds: snapshot.activeSessionIds,
          mode: snapshot.mode,
          activeModelId: snapshot.activeModelId
        }
      };
      const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
      downloadJson(`spring-ai-sessions-${timestamp}.json`, payload);
      this.status = `已导出 ${sessions.length} 个会话、${models.length} 个模型配置。迁移文件包含明文 API Key，请妥善保存。`;
    },
    async readImportFile(file) {
      if (!file) {
        return;
      }

      try {
        const text = await file.text();
        const parsed = parseImportPayload(text);
        const sessions = parsed.sessions.map(normalizeAppSession).filter(Boolean);
        if (!sessions.length) {
          throw new Error("导入文件中没有可用会话。");
        }

        this.importPayload = { ...parsed, sessions };
        this.importPreview = {
          count: sessions.length,
          modelCount: parsed.models.length,
          modes: sessions.reduce((accumulator, session) => {
            accumulator[session.mode] = (accumulator[session.mode] || 0) + 1;
            return accumulator;
          }, {}),
          exportedAt: parsed.exportedAt,
          source: parsed.source
        };
        this.status = `已读取 ${sessions.length} 个会话、${parsed.models.length} 个模型配置，确认后写入当前浏览器和后端。`;
      } catch (error) {
        this.importPayload = null;
        this.importPreview = null;
        this.status = error.message || "导入文件解析失败。";
      }
    },
    async importData() {
      if (!this.importPayload) {
        this.status = "请先选择导入文件。";
        return;
      }

      this.busy = true;
      try {
        const localSnapshot = this.readLocalSnapshot();
        const currentSessions = localSnapshot.sessions.map(normalizeAppSession).filter(Boolean);
        const nextSessions = mergeSessions(currentSessions, this.importPayload.sessions, this.importMode);
        const nextActiveSessionIds = this.importMode === "replace"
          ? this.importPayload.activeSessionIds
          : { ...localSnapshot.activeSessionIds, ...this.importPayload.activeSessionIds };
        localStorage.setItem(STORAGE_KEY, JSON.stringify(nextSessions));
        localStorage.setItem(ACTIVE_SESSION_KEY, JSON.stringify(nextActiveSessionIds || {}));
        localStorage.setItem(MODE_KEY, this.importPayload.mode || "chat");
        if (this.importPayload.activeModelId) {
          localStorage.setItem(MODEL_KEY, this.importPayload.activeModelId);
        }

        let modelMessage = "";
        try {
          await importModelsToServer(this.importPayload.models);
          if (this.importPayload.models.length) {
            modelMessage = ` 已导入 ${this.importPayload.models.length} 个模型配置。`;
          }
        } catch (error) {
          modelMessage = " 模型配置导入失败，请确认后端已启动。";
        }

        let syncMessage = "已写入本地数据。";
        try {
          await api.persistSessions(nextSessions);
          syncMessage = "已写入本地数据，并同步到后端会话库。";
        } catch (error) {
          syncMessage = `${syncMessage} 后端未连接或会话同步失败，可在启动 Spring Boot 后再次导入。`;
        }

        const sessionStore = useSessionStore();
        sessionStore.sessions = nextSessions.map(normalizeAppSession);
        sessionStore.loadLocalSessions();
        const modelStore = useModelStore();
        await modelStore.loadModels();
        this.status = `${syncMessage}${modelMessage} 页面将刷新以加载迁移数据。`;
        setTimeout(() => window.location.reload(), 700);
      } finally {
        this.busy = false;
      }
    }
  }
});
