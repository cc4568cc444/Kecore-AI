import { defineStore } from "pinia";
import { api } from "../services/api";
import { DEFAULT_AGENT_WORKING_DIRECTORY } from "../services/config";
import { useChatStore } from "./chat";
import { useModelStore } from "./model";
import { useSessionStore } from "./session";

function formatContextSize(chars) {
  const value = Number(chars || 0);
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(2)}M`;
  }
  if (value >= 1_000) {
    return `${Math.round(value / 1_000)}K`;
  }
  return String(value);
}

function normalizeContextUsage(usage) {
  if (!usage || typeof usage !== "object") {
    return null;
  }
  return {
    ...usage,
    usedTokens: Number(usage.usedTokens || 0),
    maxTokens: Number(usage.maxTokens || 0),
    percent: Number(usage.percent || 0),
    usedChars: Number(usage.usedChars || 0),
    messageCount: Number(usage.messageCount || 0),
    compacted: Boolean(usage.compacted),
    estimatedTokens: Number(usage.estimatedTokens || usage.usedTokens || 0),
    actualPromptTokens: usage.actualPromptTokens === null || usage.actualPromptTokens === undefined
      ? null
      : Number(usage.actualPromptTokens),
    toolCount: Number(usage.toolCount || 0),
    tokenSource: usage.tokenSource || "estimated",
    tokenizerModel: usage.tokenizerModel || "",
    autoCompaction: usage.autoCompaction ? {
      ...usage.autoCompaction,
      percent: Number(usage.autoCompaction.percent || 0),
      tokenPercent: Number(usage.autoCompaction.tokenPercent || 0),
      messagePercent: Number(usage.autoCompaction.messagePercent || 0),
      textPercent: Number(usage.autoCompaction.textPercent || 0),
      messageCount: Number(usage.autoCompaction.messageCount || 0),
      messageThreshold: Number(usage.autoCompaction.messageThreshold || 0),
      messagesUntilAutoCompact: Number(usage.autoCompaction.messagesUntilAutoCompact || 0),
      actualTokens: Number(usage.autoCompaction.actualTokens || 0),
      tokenThreshold: Number(usage.autoCompaction.tokenThreshold || 0),
      tokensUntilAutoCompact: Number(usage.autoCompaction.tokensUntilAutoCompact || 0),
      wouldCompact: Boolean(usage.autoCompaction.wouldCompact)
    } : null
  };
}

function normalizeLightCompactResult(result) {
  if (!result || typeof result !== "object") {
    return { compactedToolResponses: 0, compactedChars: 0 };
  }
  return {
    compactedToolResponses: Number(result.compactedToolResponses || 0),
    compactedChars: Number(result.compactedChars || 0)
  };
}

function arrayFromResponse(response) {
  if (Array.isArray(response)) {
    return response;
  }
  if (Array.isArray(response?.files)) {
    return response.files;
  }
  if (Array.isArray(response?.items)) {
    return response.items;
  }
  if (Array.isArray(response?.data)) {
    return response.data;
  }
  return [];
}

function memoryFileGroup(file) {
  if (file.id === "memory-index") {
    return "Index";
  }
  if (file.id && file.id.startsWith("memory-file:")) {
    const parts = String(file.relativePath || "").split("/");
    return parts.length >= 2 ? parts[1] : "memory";
  }
  return "Rules";
}

function memoryFileName(file) {
  if (!file) {
    return "Untitled";
  }
  if (file.id === "memory-index") {
    return "MEMORY.md";
  }
  const relativePath = file.relativePath || file.label || file.id;
  const parts = String(relativePath || "").split("/");
  return parts[parts.length - 1] || relativePath || "Untitled";
}

function normalizeMemoryFile(file) {
  const id = String(file?.id || file?.fileId || file?.path || file?.relativePath || "");
  const relativePath = file?.relativePath || file?.path || file?.label || id;
  const label = file?.label || file?.name || relativePath || id || "Untitled";
  const normalized = {
    ...(file || {}),
    id,
    label,
    relativePath,
    exists: file?.exists !== false,
    content: file?.content || ""
  };
  normalized.name = memoryFileName(normalized);
  normalized.path = relativePath || label || id;
  normalized.group = file?.group || file?.type || memoryFileGroup(normalized);
  normalized.optionLabel = `${label} | ${normalized.path}${normalized.exists ? "" : " (new)"}`;
  return normalized;
}

function normalizeMemoryFiles(response) {
  return arrayFromResponse(response).map(normalizeMemoryFile).filter((file) => file.id);
}

function preferredMemoryFileId(files, currentId) {
  if (currentId && files.some((file) => file.id === currentId)) {
    return currentId;
  }
  return files.find((file) => file.id === "memory-index")?.id
    || files.find((file) => file.id === "project")?.id
    || files[0]?.id
    || "";
}

function memoryGroupOrder(group) {
  const order = ["Rules", "Index", "user", "feedback", "project", "reference", "memory"];
  const index = order.indexOf(group);
  return index === -1 ? order.length : index;
}

export const useWorkspaceStore = defineStore("workspace", {
  state: () => ({
    contextDrawerOpen: false,
    contextTab: "messages",
    contextPreview: null,
    contextStatus: "未加载",
    contextCompacting: false,
    contextCompactionMode: "",
    mcpServers: [],
    memoryOpen: false,
    memoryFiles: [],
    selectedMemoryFileId: "",
    memoryEditor: "",
    memoryStatus: "读取当前工作目录规则和全局自动记忆。",
    memoryCreateForm: {
      type: "user",
      name: "",
      description: "",
      opportunity: "",
      content: ""
    },
    skillsOpen: false,
    skills: [],
    skillSearch: "",
    selectedSkillCommand: "",
    selectedSkillFile: null,
    skillArguments: "",
    skillPreview: "",
    skillRenderedPreview: "",
    skillsStatus: "读取当前工作目录的 .claude/skills 和 .agents/skills。"
  }),
  getters: {
    activeSession: () => useSessionStore().activeSession,
    workingDirectory() {
      return this.activeSession?.workingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY;
    },
    contextUsage() {
      return this.activeSession?.contextUsage || null;
    },
    contextPercent() {
      const usage = this.contextUsage;
      if (!usage?.maxTokens) {
        return 0;
      }
      const percent = Number.isFinite(Number(usage.percent))
        ? Number(usage.percent)
        : (Number(usage.usedTokens || 0) / Number(usage.maxTokens)) * 100;
      return Math.max(0, Math.min(100, percent));
    },
    contextUsageLabel() {
      return `上下文 ${this.contextPercent.toFixed(2)}%`;
    },
    contextUsagePillLabel() {
      return `上下文 ${this.contextPercent.toFixed(2)}%`;
    },
    contextUsageDetail() {
      const usage = this.contextUsage;
      if (!usage) {
        return "0 / 1M";
      }
      const source = usage.tokenSource === "model-usage" ? "usage" : "估算";
      const estimate = usage.actualPromptTokens !== null && usage.actualPromptTokens !== undefined
        ? ` · 估算 ${formatContextSize(usage.estimatedTokens || 0)}`
        : "";
      return `${formatContextSize(usage.usedTokens || 0)} / ${formatContextSize(usage.maxTokens || 1_000_000)} token · ${source}${estimate}`;
    },
    autoCompactionPercent() {
      const auto = this.contextUsage?.autoCompaction || {};
      const percent = Number(auto.tokenPercent || auto.percent || 0);
      return Math.max(0, Math.min(100, percent));
    },
    autoCompactionLabel() {
      const percent = this.autoCompactionPercent;
      if (this.contextUsage?.autoCompaction?.wouldCompact) {
        return "自动压缩 已达到";
      }
      return `自动压缩 ${percent.toFixed(percent < 1 && percent > 0 ? 2 : 1)}%`;
    },
    autoCompactionPillLabel() {
      const percent = this.autoCompactionPercent;
      if (this.contextUsage?.autoCompaction?.wouldCompact) {
        return "自动 已达到";
      }
      return `自动 ${percent.toFixed(percent < 1 && percent > 0 ? 2 : 1)}%`;
    },
    autoCompactionDetail() {
      const auto = this.contextUsage?.autoCompaction || {};
      const messageCount = Number(auto.messageCount || 0);
      const messageThreshold = Number(auto.messageThreshold || 256);
      const actualTokens = Number(auto.actualTokens || 0);
      const tokenThreshold = Number(auto.tokenThreshold || 128_000);
      return `${messageCount}/${messageThreshold} 条 · ${formatContextSize(actualTokens)} / ${formatContextSize(tokenThreshold)} token`;
    },
    selectedMemoryFile() {
      return this.memoryFiles.find((file) => file.id === this.selectedMemoryFileId) || null;
    },
    memoryFileOptions() {
      return this.memoryFiles.map((file) => ({
        label: file.optionLabel || file.name || file.path || file.id,
        buttonLabel: file.name || file.label || file.id,
        value: file.id
      }));
    },
    memoryFileGroups() {
      const grouped = new Map();
      this.memoryFiles.forEach((file) => {
        const group = file.group || memoryFileGroup(file);
        if (!grouped.has(group)) {
          grouped.set(group, []);
        }
        grouped.get(group).push(file);
      });
      return [...grouped.entries()]
        .sort(([left], [right]) => memoryGroupOrder(left) - memoryGroupOrder(right) || left.localeCompare(right))
        .map(([group, files]) => ({ group, files }));
    },
    filteredSkills() {
      const query = this.skillSearch.trim().toLowerCase();
      if (!query) {
        return this.skills;
      }
      return this.skills.filter((skill) =>
        String(skill.commandName || skill.name || "").toLowerCase().includes(query)
        || String(skill.description || "").toLowerCase().includes(query)
      );
    },
    selectedSkill() {
      return this.skills.find((skill) => skill.commandName === this.selectedSkillCommand) || null;
    }
  },
  actions: {
    contextParams() {
      const session = this.activeSession;
      const models = useModelStore();
      return {
        conversationId: session?.id || "",
        workingDirectory: session?.workingDirectory || this.workingDirectory,
        modelId: models.currentModelId || ""
      };
    },
    async loadContextUsage() {
      const session = this.activeSession;
      if (!session || session.mode !== "agent") {
        return;
      }
      try {
        session.contextUsage = normalizeContextUsage(await api.loadAgentContext(this.contextParams()));
        useSessionStore().saveSessions();
      } catch (error) {
        console.warn("加载上下文用量失败:", error);
      }
    },
    async openContextDrawer() {
      this.contextDrawerOpen = true;
      this.contextStatus = "加载中";
      try {
        this.contextPreview = await api.loadAgentContextPreview(this.contextParams());
        this.contextStatus = "已加载";
      } catch (error) {
        this.contextStatus = error.message || "上下文加载失败";
      }
    },
    closeContextDrawer() {
      this.contextDrawerOpen = false;
    },
    async compactContext(light = false) {
      const session = this.activeSession;
      if (!session) {
        return;
      }
      this.contextCompacting = true;
      this.contextCompactionMode = light ? "light" : "full";
      useSessionStore().setStatus(light ? "正在轻量压缩上下文" : "正在压缩上下文");
      try {
        const payload = this.contextParams();
        const result = light
          ? await api.lightCompactAgentContext(payload)
          : await api.compactAgentContext(payload);
        if (light) {
          const compacted = normalizeLightCompactResult(result);
          session.lastLightCompactedAt = Date.now();
          session.lastLightCompactionCheckpointAt = session.lastLightCompactedAt;
          await this.loadContextUsage();
          useSessionStore().setStatus(
            compacted.compactedToolResponses > 0
              ? `轻量压缩已完成 ${compacted.compactedToolResponses} 个工具输出`
              : "轻量压缩已标记完成"
          );
        } else {
          session.contextUsage = normalizeContextUsage(result);
          useSessionStore().setStatus("上下文已压缩");
        }
        useSessionStore().saveSessions();
      } catch (error) {
        console.warn(light ? "轻量压缩失败:" : "上下文压缩失败:", error);
        useSessionStore().setStatus(light ? "轻量压缩失败" : "上下文压缩失败");
      } finally {
        this.contextCompacting = false;
        this.contextCompactionMode = "";
      }
    },
    async loadMcpServers() {
      try {
        this.mcpServers = await api.loadMcpServers();
      } catch (error) {
        this.mcpServers = [];
      }
    },
    async openMemory() {
      this.memoryOpen = true;
      await this.loadMemoryFiles();
    },
    closeMemory() {
      this.memoryOpen = false;
    },
    async loadMemoryFiles() {
      this.memoryStatus = "正在读取记忆文件";
      try {
        this.memoryFiles = normalizeMemoryFiles(await api.loadMemoryFiles(this.workingDirectory));
        this.selectedMemoryFileId = preferredMemoryFileId(this.memoryFiles, this.selectedMemoryFileId);
        this.loadSelectedMemoryFile();
        this.memoryStatus = `已读取 ${this.memoryFiles.length} 个记忆文件。`;
      } catch (error) {
        this.memoryStatus = error.message || "记忆文件读取失败";
      }
    },
    loadSelectedMemoryFile() {
      const file = this.selectedMemoryFile;
      this.memoryEditor = file?.content || "";
    },
    async saveSelectedMemoryFile() {
      const file = this.selectedMemoryFile;
      if (!file) {
        return;
      }
      const updated = await api.saveMemoryFile({
        workingDirectory: this.workingDirectory,
        fileId: file.id,
        content: this.memoryEditor
      });
      Object.assign(file, normalizeMemoryFile(updated));
      this.selectedMemoryFileId = file.id;
      this.memoryStatus = "记忆文件已保存";
    },
    async createMemory() {
      await api.createMemory({
        workingDirectory: this.workingDirectory,
        ...this.memoryCreateForm
      });
      this.memoryCreateForm = { type: "user", name: "", description: "", opportunity: "", content: "" };
      await this.loadMemoryFiles();
      this.memoryStatus = "记忆已创建";
    },
    async openSkills() {
      this.skillsOpen = true;
      await this.loadSkills();
    },
    closeSkills() {
      this.skillsOpen = false;
    },
    async loadSkills() {
      this.skillsStatus = "正在读取 Skills";
      try {
        this.skills = await api.loadSkills(this.workingDirectory);
        if (!this.selectedSkillCommand && this.skills.length) {
          this.selectedSkillCommand = this.skills[0].commandName;
        }
        await this.loadSelectedSkill();
        this.skillsStatus = `已读取 ${this.skills.length} 个 Skill。`;
      } catch (error) {
        this.skillsStatus = error.message || "Skills 读取失败";
      }
    },
    async loadSelectedSkill() {
      if (!this.selectedSkillCommand) {
        this.selectedSkillFile = null;
        this.skillPreview = "";
        return;
      }
      this.selectedSkillFile = await api.loadSkillFile(this.workingDirectory, this.selectedSkillCommand);
      this.skillPreview = this.selectedSkillFile?.content || "";
      this.skillRenderedPreview = "";
    },
    async renderSelectedSkill() {
      if (!this.selectedSkillCommand) {
        return;
      }
      const rendered = await api.renderSkill(this.workingDirectory, this.selectedSkillCommand, this.skillArguments);
      this.skillRenderedPreview = rendered?.content || rendered?.rendered || String(rendered || "");
    },
    async saveSelectedSkillFile() {
      if (!this.selectedSkillFile) {
        return;
      }
      this.selectedSkillFile = await api.saveSkillFile({
        ...this.selectedSkillFile,
        workingDirectory: this.workingDirectory,
        content: this.skillPreview
      });
      this.skillsStatus = "Skill 已保存";
    },
    newSkillFile() {
      this.selectedSkillCommand = "";
      this.selectedSkillFile = {
        commandName: "",
        path: "",
        content: "# New Skill\n\nDescribe this skill."
      };
      this.skillPreview = this.selectedSkillFile.content;
      this.skillsStatus = "正在新建 Skill 文件";
    },
    async deleteSelectedSkill() {
      if (!this.selectedSkillCommand) {
        return;
      }
      await api.deleteSkill(this.workingDirectory, this.selectedSkillCommand);
      this.selectedSkillCommand = "";
      await this.loadSkills();
      this.skillsStatus = "Skill 已删除";
    },
    insertSkillInvocation() {
      const chat = useChatStore();
      const command = this.selectedSkillCommand ? `/${this.selectedSkillCommand}` : "";
      const text = this.skillArguments ? `${command} ${this.skillArguments}` : command;
      if (text) {
        chat.setPrompt(chat.prompt ? `${chat.prompt}\n${text}` : text);
      }
      this.closeSkills();
    },
    async copySkillPreview() {
      const text = this.skillRenderedPreview || this.skillPreview;
      if (text) {
        await navigator.clipboard?.writeText(text);
        this.skillsStatus = "已复制";
      }
    }
  }
});
