import { defineStore } from "pinia";
import { api } from "../services/api";
import {
  ACTIVE_SESSION_KEY,
  DEFAULT_AGENT_WORKING_DIRECTORY,
  MODE_KEY,
  MODES,
  STORAGE_KEY,
  visibleMode
} from "../services/config";
import {
  createSession,
  normalizeActiveSessionIds,
  normalizeApprovalMode,
  normalizeSandboxEnabled,
  normalizeSession,
  serializableSessions
} from "../utils/session";

function readJsonStorage(key, fallback) {
  try {
    const rawValue = localStorage.getItem(key);
    return rawValue ? JSON.parse(rawValue) : fallback;
  } catch (error) {
    return fallback;
  }
}

function syncDocumentMode(mode) {
  const visible = visibleMode(mode);
  document.body.dataset.mode = visible;
  document.documentElement.setAttribute("data-initial-mode", visible);
}

export const useSessionStore = defineStore("session", {
  state: () => ({
    mode: visibleMode(localStorage.getItem(MODE_KEY) || "chat"),
    sessions: [],
    activeSessionIds: normalizeActiveSessionIds(readJsonStorage(ACTIVE_SESSION_KEY, {})),
    status: "准备就绪",
    persistenceReady: false,
    saveTimer: null,
    defaultWorkingDirectory: DEFAULT_AGENT_WORKING_DIRECTORY,
    workspaceExpanded: false,
    workspaceModalOpen: false,
    workspaceModalCreateNew: false
  }),
  getters: {
    modeMeta: (state) => MODES[state.mode] || MODES.chat,
    title: (state) => (MODES[state.mode] || MODES.chat).title,
    placeholder: (state) => (MODES[state.mode] || MODES.chat).placeholder,
    emptyTitle: (state) => (MODES[state.mode] || MODES.chat).emptyTitle,
    sessionsForMode: (state) => state.sessions.filter((session) => session.mode === state.mode),
    activeSession(state) {
      return state.sessions.find((session) => session.id === state.activeSessionIds[state.mode]) || null;
    },
    hasMessages() {
      return Boolean(this.activeSession?.messages?.length);
    }
  },
  actions: {
    setStatus(text) {
      this.status = text || "准备就绪";
    },
    async initialize() {
      this.loadLocalSessions();
      try {
        const saved = await api.listSessions();
        if (Array.isArray(saved) && saved.length > 0) {
          this.sessions = saved.map(normalizeSession);
        }
      } catch (error) {
        console.warn("加载后端会话失败，使用本地缓存:", error);
      }
      try {
        const result = await api.defaultWorkingDirectory();
        this.defaultWorkingDirectory = result?.workingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY;
      } catch (error) {
        this.defaultWorkingDirectory = DEFAULT_AGENT_WORKING_DIRECTORY;
      }
      this.ensureSession(this.mode);
      this.persistenceReady = true;
      this.saveSessions();
    },
    loadLocalSessions() {
      const saved = readJsonStorage(STORAGE_KEY, []);
      this.sessions = Array.isArray(saved) ? saved.map(normalizeSession) : [];
      this.activeSessionIds = normalizeActiveSessionIds(readJsonStorage(ACTIVE_SESSION_KEY, {}));
      this.mode = visibleMode(localStorage.getItem(MODE_KEY) || this.mode);
    },
    ensureSession(mode = this.mode) {
      const sessionMode = visibleMode(mode);
      const activeId = this.activeSessionIds[sessionMode];
      let session = this.sessions.find((item) => item.id === activeId && item.mode === sessionMode);
      session = session || this.sessions.find((item) => item.mode === sessionMode);
      if (!session) {
        session = createSession(sessionMode);
        if (sessionMode === "agent") {
          session.workingDirectory = this.defaultWorkingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY;
        }
        this.sessions.unshift(session);
      }
      if (sessionMode === "agent" && !session.workingDirectory) {
        session.workingDirectory = this.defaultWorkingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY;
      }
      this.activeSessionIds[sessionMode] = session.id;
      return session;
    },
    changeMode(nextMode) {
      this.mode = visibleMode(nextMode);
      syncDocumentMode(this.mode);
      this.ensureSession(this.mode);
      this.workspaceExpanded = this.mode === "agent" ? this.workspaceExpanded : false;
      this.saveSessions();
      this.setStatus("准备就绪");
    },
    switchSession(sessionId) {
      const session = this.sessions.find((item) => item.id === sessionId);
      if (!session) {
        return;
      }
      this.mode = visibleMode(session.mode);
      this.activeSessionIds[this.mode] = session.id;
      syncDocumentMode(this.mode);
      this.saveSessions();
      this.setStatus("准备就绪");
    },
    startNewSession({ workingDirectory = "", approvalMode = "work-auto", sandboxEnabled = true } = {}) {
      const session = createSession(this.mode);
      if (this.mode === "agent") {
        session.workingDirectory = workingDirectory || this.defaultWorkingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY;
      }
      session.approvalMode = normalizeApprovalMode(approvalMode);
      session.sandboxEnabled = normalizeSandboxEnabled(sandboxEnabled, session.approvalMode);
      this.sessions.unshift(session);
      this.activeSessionIds[this.mode] = session.id;
      this.workspaceModalOpen = false;
      this.saveSessions();
    },
    requestNewSession() {
      if (this.mode === "agent") {
        this.workspaceModalCreateNew = true;
        this.workspaceModalOpen = true;
        return;
      }
      this.startNewSession();
    },
    clearCurrentSession() {
      const session = this.activeSession;
      if (!session) {
        return;
      }
      const previousId = session.id;
      const fresh = createSession(this.mode);
      Object.assign(session, {
        ...fresh,
        id: fresh.id,
        workingDirectory: this.mode === "agent"
          ? (session.workingDirectory || this.defaultWorkingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY)
          : ""
      });
      this.activeSessionIds[this.mode] = session.id;
      api.deleteSession(previousId).catch(() => {});
      this.saveSessions();
    },
    async deleteSession(sessionId) {
      const index = this.sessions.findIndex((session) => session.id === sessionId);
      if (index < 0) {
        return;
      }
      const [removed] = this.sessions.splice(index, 1);
      if (this.activeSessionIds[removed.mode] === sessionId) {
        const next = this.sessions.find((session) => session.mode === removed.mode);
        this.activeSessionIds[removed.mode] = next?.id || null;
      }
      this.ensureSession(this.mode);
      this.saveSessions();
      try {
        await api.deleteSession(sessionId);
      } catch (error) {
        console.warn("删除后端会话失败:", error);
      }
    },
    saveSessions() {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(serializableSessions(this.sessions)));
      localStorage.setItem(ACTIVE_SESSION_KEY, JSON.stringify(this.activeSessionIds));
      localStorage.setItem(MODE_KEY, this.mode);
      syncDocumentMode(this.mode);
      if (!this.persistenceReady) {
        return;
      }
      clearTimeout(this.saveTimer);
      this.saveTimer = setTimeout(() => this.persistSessions(), 250);
    },
    async persistSessions() {
      try {
        await api.persistSessions(serializableSessions(this.sessions));
      } catch (error) {
        console.warn("保存会话到数据库失败:", error);
      }
    },
    updateActiveSession(patch) {
      const session = this.activeSession;
      if (!session) {
        return;
      }
      Object.assign(session, patch, { updatedAt: Date.now() });
      this.saveSessions();
    },
    openWorkspaceModal(createNewSession = false) {
      this.workspaceModalCreateNew = createNewSession;
      this.workspaceModalOpen = true;
    },
    closeWorkspaceModal() {
      this.workspaceModalOpen = false;
      this.workspaceModalCreateNew = false;
    }
  }
});
