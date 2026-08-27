import { defineStore } from "pinia";
import { api } from "../services/api";
import { MODES } from "../services/config";
import {
  agentRoundProgressStatusText,
  applyAgentRoundProgress,
  applyTaskProgress,
  mergeToolBatchesPreservingHistory
} from "../utils/agent";
import { readSse } from "../utils/sse";
import {
  appendReasoning,
  applyTokenUsage,
  assistantMessage,
  finishMessageLatency,
  finishReasoningDisplay,
  markTokenOutput,
  prepareReasoningDisplay,
  estimateTokensFromText,
  userMessage,
  normalizeApprovalMode,
  normalizeSandboxEnabled
} from "../utils/session";
import { useModelStore } from "./model";
import { useSessionStore } from "./session";

function wantsReasoningOutput(modelStore) {
  const options = modelStore.runtimeOptionsPayload();
  return Boolean(options.reasoningEffort || options.thinkingType === "enabled");
}

function mergeAgentFinalText(existingText, finalText) {
  const existing = existingText || "";
  const final = finalText || "";
  if (!existing.trim()) {
    return final;
  }
  if (!final.trim()) {
    return existing;
  }
  const existingTrimmed = existing.trim();
  const finalTrimmed = final.trim();
  if (existingTrimmed === finalTrimmed) {
    return existing;
  }
  if (finalTrimmed.startsWith(existingTrimmed)) {
    return final;
  }
  if (existingTrimmed.includes(finalTrimmed)) {
    return existing;
  }
  return `${existing.trimEnd()}\n\n${final.trimStart()}`;
}

function touchSession(session, sessions) {
  session.updatedAt = Date.now();
  sessions.saveSessions();
}

function touchSessionSoft(session) {
  session.updatedAt = Date.now();
}

function createStreamUpdateScheduler(session, sessions, delay = 800) {
  let timer = null;
  return {
    touch({ persist = false } = {}) {
      if (persist) {
        clearTimeout(timer);
        timer = null;
        touchSession(session, sessions);
        return;
      }
      if (!timer) {
        touchSessionSoft(session);
        timer = setTimeout(() => {
          timer = null;
          touchSession(session, sessions);
        }, delay);
      }
    },
    flush() {
      clearTimeout(timer);
      timer = null;
      touchSession(session, sessions);
    }
  };
}

function createStreamTextBuffer(onFlush, delay = 80) {
  let timer = null;
  let buffer = "";
  const flush = () => {
    clearTimeout(timer);
    timer = null;
    if (!buffer) {
      return;
    }
    const content = buffer;
    buffer = "";
    onFlush(content);
  };
  return {
    append(content) {
      if (!content) {
        return;
      }
      buffer += content;
      if (!timer) {
        timer = setTimeout(flush, delay);
      }
    },
    flush
  };
}

function prepareMessageStreamingState(message) {
  message.thinking = true;
  message.streaming = true;
  message.completedAt = null;
  message.startedAt = Date.now();
  message.firstTokenAt = null;
  message.lastTokenAt = null;
  message.latencyMs = null;
  message.serverLatencyMs = null;
  message.outputTokenEstimate = 0;
  message.tokenUsage = null;
}

async function contextCompactionInProgress() {
  const { useWorkspaceStore } = await import("./workspace");
  return useWorkspaceStore().contextCompacting;
}

export const useChatStore = defineStore("chat", {
  state: () => ({
    prompt: "",
    pendingFiles: [],
    running: false,
    abortController: null,
    agentInteraction: null,
    imagePreviewUrl: "",
    messagesPinnedToBottom: true
  }),
  getters: {
    attachments: (state) => state.pendingFiles.map((file, index) => ({ index, name: file.name }))
  },
  actions: {
    setPrompt(value) {
      this.prompt = value;
    },
    addFiles(fileList) {
      this.pendingFiles = [...this.pendingFiles, ...Array.from(fileList || [])];
    },
    removeFile(index) {
      this.pendingFiles.splice(index, 1);
    },
    clearAttachments() {
      this.pendingFiles = [];
    },
    stop() {
      this.abortController?.abort();
    },
    async submitPrompt(text = this.prompt) {
      const prompt = String(text || "").trim();
      if (!prompt || this.running) {
        return;
      }
      const sessions = useSessionStore();
      if (await contextCompactionInProgress()) {
        sessions.setStatus("上下文压缩中，暂不可发送消息");
        return;
      }
      const models = useModelStore();
      const requestMode = sessions.mode;
      const session = sessions.ensureSession(requestMode);
      if (requestMode === "agent" && !session.workingDirectory) {
        sessions.setStatus("请先选择默认工作目录");
        sessions.openWorkspaceModal(false);
        return;
      }

      const attachments = requestMode === "agent" ? this.pendingFiles.map((file) => file.name) : [];
      const user = userMessage(prompt, attachments);
      user.mode = requestMode;
      session.messages.push(user);
      session.messages.push(assistantMessage());
      const assistant = session.messages[session.messages.length - 1];
      assistant.mode = requestMode;
      prepareMessageStreamingState(assistant);
      assistant.inputTokenEstimate = estimateTokensFromText(prompt);
      prepareReasoningDisplay(assistant, wantsReasoningOutput(models));
      session.updatedAt = Date.now();
      if (!session.title || session.title === MODES[requestMode]?.sessionTitle) {
        session.title = prompt.slice(0, 28) || MODES[requestMode].sessionTitle;
      }
      this.prompt = "";
      sessions.saveSessions();

      this.abortController = new AbortController();
      this.running = true;
      sessions.setStatus(MODES[requestMode].generating);

      try {
        if (requestMode === "agent") {
          session.lastAgentSentAt = Date.now();
          await this.submitAgentPrompt(prompt, session, assistant);
        } else {
          await this.submitModelPrompt(prompt, session, assistant, requestMode);
        }
        sessions.setStatus("已完成");
      } catch (error) {
        this.applyStreamError(error, assistant, sessions);
      } finally {
        if (!assistant.thinking && assistant.latencyMs === null) {
          finishMessageLatency(assistant);
          sessions.saveSessions();
        }
        if (requestMode === "agent") {
          const { useWorkspaceStore } = await import("./workspace");
          await useWorkspaceStore().loadContextUsage();
        }
        this.abortController = null;
        this.running = false;
      }
    },
    async regenerateUserMessage(message) {
      const prompt = String(message?.text || "").trim();
      if (!prompt || this.running) {
        return;
      }
      const sessions = useSessionStore();
      if (await contextCompactionInProgress()) {
        sessions.setStatus("上下文压缩中，暂不可刷新消息");
        return;
      }
      const session = sessions.activeSession;
      if (!session || !Array.isArray(session.messages)) {
        return;
      }
      const userIndex = session.messages.findIndex((item) => item?.id === message.id);
      const latestUserIndex = session.messages.findLastIndex((item) => item?.role === "user");
      if (userIndex < 0 || userIndex !== latestUserIndex || session.messages[userIndex]?.role !== "user") {
        return;
      }
      const models = useModelStore();
      const requestMode = session.mode || sessions.mode;
      if (requestMode === "agent" && !session.workingDirectory) {
        sessions.setStatus("请先选择默认工作目录");
        sessions.openWorkspaceModal(false);
        return;
      }

      session.messages.splice(userIndex + 1);
      session.messages.push(assistantMessage());
      const assistant = session.messages[session.messages.length - 1];
      assistant.mode = requestMode;
      prepareMessageStreamingState(assistant);
      assistant.inputTokenEstimate = estimateTokensFromText(prompt);
      prepareReasoningDisplay(assistant, wantsReasoningOutput(models));
      session.updatedAt = Date.now();
      sessions.saveSessions();

      this.abortController = new AbortController();
      this.running = true;
      sessions.setStatus(MODES[requestMode].generating);

      try {
        if (requestMode === "agent") {
          session.lastAgentSentAt = Date.now();
          await this.submitAgentPrompt(prompt, session, assistant, { includeAttachments: false, regenerate: true });
        } else {
          await this.submitModelPrompt(prompt, session, assistant, requestMode, true);
        }
        sessions.setStatus("已完成");
      } catch (error) {
        this.applyStreamError(error, assistant, sessions);
      } finally {
        if (!assistant.thinking && assistant.latencyMs === null) {
          finishMessageLatency(assistant);
          sessions.saveSessions();
        }
        if (requestMode === "agent") {
          const { useWorkspaceStore } = await import("./workspace");
          await useWorkspaceStore().loadContextUsage();
        }
        this.abortController = null;
        this.running = false;
      }
    },
    async submitModelPrompt(prompt, session, assistant, requestMode, regenerate = false) {
      const sessions = useSessionStore();
      const models = useModelStore();
      const params = new URLSearchParams({
        prompt,
        conv_id: session.id,
        modelId: models.currentModelId
      });
      const runtime = models.runtimeOptionsPayload();
      if (runtime.reasoningEffort) {
        params.set("reasoningEffort", runtime.reasoningEffort);
      }
      if (runtime.thinkingType) {
        params.set("thinkingType", runtime.thinkingType);
      }
      if (runtime.extraBody) {
        params.set("extraBody", runtime.extraBody);
      }
      if (requestMode === "finance") {
        params.set("retrievalStrategy", session.financeRetrievalStrategy || "default");
      }
      if (requestMode === "paper") {
        params.set("retrievalStrategy", session.paperRetrievalStrategy || "parent-child");
      }
      if (regenerate) {
        params.set("regenerate", "true");
      }

      const response = requestMode === "finance"
        ? await api.streamFinanceChat(params, this.abortController.signal)
        : requestMode === "paper"
          ? await api.streamPaperChat(params, this.abortController.signal)
          : await api.streamChat(params, this.abortController.signal);

      const streamUpdates = createStreamUpdateScheduler(session, sessions);
      let terminalEventReceived = false;
      const tokenBuffer = createStreamTextBuffer((content) => {
        assistant.text += content;
        markTokenOutput(assistant, content);
      });
      await readSse(response, (event, data) => {
        if (event === "metrics") {
          assistant.translationMs = data.translationMs ?? null;
          assistant.retrievalMs = data.retrievalMs ?? null;
          assistant.retrievalQuery = data.retrievalQuery || "";
          streamUpdates.touch({ persist: true });
        } else if (event === "reasoning") {
          assistant.thinking = false;
          appendReasoning(assistant, data.content || "");
          streamUpdates.touch();
        } else if (event === "token") {
          assistant.thinking = false;
          tokenBuffer.append(data.content || "");
          streamUpdates.touch();
        } else if (event === "complete") {
          terminalEventReceived = true;
          tokenBuffer.flush();
          assistant.thinking = false;
          finishReasoningDisplay(assistant);
          assistant.translationMs = data.translationMs ?? assistant.translationMs;
          assistant.retrievalMs = data.retrievalMs ?? assistant.retrievalMs;
          assistant.modelResponseMs = data.modelResponseMs ?? assistant.modelResponseMs;
          assistant.retrievalQuery = data.retrievalQuery || assistant.retrievalQuery || "";
          applyTokenUsage(assistant, data.tokenUsage);
          finishMessageLatency(assistant, data.latencyMs);
          assistant.streaming = false;
          assistant.completedAt = Date.now();
          streamUpdates.flush();
        } else if (event === "error") {
          terminalEventReceived = true;
          tokenBuffer.flush();
          assistant.thinking = false;
          assistant.streaming = false;
          assistant.error = true;
          finishReasoningDisplay(assistant);
          assistant.text = data.message || "请求失败";
          applyTokenUsage(assistant, data.tokenUsage);
          finishMessageLatency(assistant, data.latencyMs);
          assistant.completedAt = Date.now();
          streamUpdates.flush();
        }
      });
      tokenBuffer.flush();
      if (!terminalEventReceived) {
        throw new Error("连接已经结束，但后端没有发送完成或错误事件。请查看后端日志后重试。");
      }
      if (!assistant.error && !String(assistant.text || "").trim()) {
        throw new Error("模型请求已完成，但没有返回答案文本。请检查模型接口或更换模型后重试。");
      }
      assistant.thinking = false;
      assistant.streaming = false;
      finishReasoningDisplay(assistant);
      finishMessageLatency(assistant);
      assistant.completedAt = assistant.completedAt || Date.now();
      streamUpdates.flush();
    },
    async submitAgentPrompt(prompt, session, assistant, options = {}) {
      const normalizedOptions = typeof options === "boolean" ? { includeAttachments: options } : (options || {});
      const includeAttachments = normalizedOptions.includeAttachments !== false;
      const regenerate = Boolean(normalizedOptions.regenerate);
      const models = useModelStore();
      const formData = new FormData();
      formData.append("prompt", prompt);
      formData.append("conversationId", session.id);
      formData.append("workingDirectory", session.workingDirectory);
      formData.append("approvalMode", normalizeApprovalMode(session.approvalMode));
      formData.append("sandboxEnabled", normalizeSandboxEnabled(session.sandboxEnabled, session.approvalMode));
      formData.append("modelId", models.currentModelId);
      if (session.lastLightCompactedAt) {
        formData.append("lightCompactedBefore", session.lastLightCompactedAt);
      }
      if (regenerate) {
        formData.append("regenerate", "true");
      }
      const runtime = models.runtimeOptionsPayload();
      if (runtime.reasoningEffort) {
        formData.append("reasoningEffort", runtime.reasoningEffort);
      }
      if (runtime.thinkingType) {
        formData.append("thinkingType", runtime.thinkingType);
      }
      if (runtime.extraBody) {
        formData.append("extraBody", runtime.extraBody);
      }
      if (includeAttachments) {
        this.pendingFiles.forEach((file) => formData.append("files", file));
        this.clearAttachments();
      }
      const response = await api.streamAgentChat(formData, this.abortController.signal);
      await this.processAgentStream(response, assistant, session);
    },
    async processAgentStream(response, assistant, session) {
      const sessions = useSessionStore();
      let terminalEventReceived = false;
      let separateNextToken = false;
      const streamUpdates = createStreamUpdateScheduler(session, sessions);
      const appendAgentToken = (content) => {
        if (!content) {
          return;
        }
        if (separateNextToken && assistant.text && assistant.text.trim()) {
          assistant.text = `${assistant.text.trimEnd()}\n\n`;
        }
        separateNextToken = false;
        assistant.text += content;
        markTokenOutput(assistant, content);
      };
      const tokenBuffer = createStreamTextBuffer((content) => appendAgentToken(content));
      const finishStream = () => {
        tokenBuffer.flush();
        assistant.thinking = false;
        assistant.streaming = false;
        assistant.completedAt = Date.now();
        finishReasoningDisplay(assistant);
        streamUpdates.flush();
      };

      await readSse(response, (event, data) => {
        if (event === "reasoning") {
          assistant.thinking = false;
          appendReasoning(assistant, data.content || "");
          streamUpdates.touch();
        } else if (event === "token") {
          assistant.thinking = false;
          tokenBuffer.append(data.content);
          streamUpdates.touch();
        } else if (event === "activity") {
          tokenBuffer.flush();
          assistant.thinking = false;
          separateNextToken = true;
          assistant.activities = data.activities || [];
          assistant.toolBatches = data.executions
            ? mergeToolBatchesPreservingHistory(assistant.toolBatches, data.executions)
            : assistant.toolBatches || [];
          streamUpdates.touch({ persist: true });
        } else if (event === "task_progress") {
          tokenBuffer.flush();
          applyTaskProgress(assistant, data);
          sessions.setStatus("子 agent 执行中");
          streamUpdates.touch();
        } else if (event === "loop_progress") {
          tokenBuffer.flush();
          applyAgentRoundProgress(assistant, data);
          sessions.setStatus(agentRoundProgressStatusText(data.status));
          streamUpdates.touch();
        } else if (event === "approval_required") {
          tokenBuffer.flush();
          terminalEventReceived = true;
          assistant.text = "需要确认后才能继续执行。";
          assistant.thinking = false;
          finishReasoningDisplay(assistant);
          assistant.activities = data.activities || [];
          assistant.toolBatches = assistant.toolBatches || [];
          assistant.toolBatches.push({
            runId: data.runId,
            tools: data.tools || [],
            status: "pending"
          });
          sessions.setStatus("等待工具确认");
          finishMessageLatency(assistant, data.latencyMs);
          finishStream();
        } else if (event === "interaction_required") {
          tokenBuffer.flush();
          terminalEventReceived = true;
          const isLoopLimit = data.status === "loop_limit_required";
          assistant.text = isLoopLimit ? "已达到当前循环上限，需要确认是否继续。" : "需要你的输入后才能继续。";
          assistant.thinking = false;
          finishReasoningDisplay(assistant);
          assistant.activities = data.activities || [];
          assistant.toolBatches = assistant.toolBatches || [];
          assistant.toolBatches.push({
            runId: data.runId,
            tools: data.tools || [],
            status: isLoopLimit ? "loop_limit" : "waiting_input",
            interaction: data.interaction
          });
          sessions.setStatus(isLoopLimit ? "等待继续确认" : "等待用户输入");
          this.agentInteraction = data.interaction;
          finishMessageLatency(assistant, data.latencyMs);
          finishStream();
        } else if (event === "complete") {
          tokenBuffer.flush();
          terminalEventReceived = true;
          assistant.text = mergeAgentFinalText(assistant.text, data.content || "");
          appendReasoning(assistant, data.reasoning || "");
          finishReasoningDisplay(assistant);
          assistant.activities = data.activities || assistant.activities;
          assistant.toolBatches = data.executions
            ? mergeToolBatchesPreservingHistory(assistant.toolBatches, data.executions)
            : assistant.toolBatches;
          assistant.thinking = false;
          applyTokenUsage(assistant, data.tokenUsage);
          finishMessageLatency(assistant, data.latencyMs);
          if (assistant.agentRoundProgress) {
            assistant.agentRoundProgress.status = "completed";
          }
          sessions.setStatus("已完成");
          finishStream();
        } else if (event === "error") {
          tokenBuffer.flush();
          terminalEventReceived = true;
          assistant.thinking = false;
          assistant.error = true;
          finishReasoningDisplay(assistant);
          assistant.text = data.message || "请求失败";
          if (assistant.agentRoundProgress) {
            assistant.agentRoundProgress.status = "failed";
          }
          applyTokenUsage(assistant, data.tokenUsage);
          finishMessageLatency(assistant, data.latencyMs);
          sessions.setStatus("请求失败");
          finishStream();
        }
      });

      tokenBuffer.flush();
      finishReasoningDisplay(assistant);
      if (!terminalEventReceived && assistant.thinking) {
        assistant.thinking = false;
        assistant.error = true;
        assistant.text = assistant.text || "连接已结束，但没有收到最终完成事件。请查看后端日志确认本轮是否被中断。";
        sessions.setStatus("连接已结束");
      }
      assistant.thinking = false;
      assistant.streaming = false;
      assistant.completedAt = assistant.completedAt || Date.now();
      finishMessageLatency(assistant);
      streamUpdates.flush();
    },
    findAssistantByRunId(runId) {
      const sessions = useSessionStore();
      for (const session of sessions.sessions) {
        for (const message of session.messages || []) {
          const batch = (message.toolBatches || []).find((item) => item.runId === runId);
          if (batch) {
            return { session, message, batch };
          }
        }
      }
      return null;
    },
    async decideToolAction(runId, action) {
      if (this.running) {
        return;
      }
      const sessions = useSessionStore();
      if (await contextCompactionInProgress()) {
        sessions.setStatus("上下文压缩中，暂不可继续执行");
        return;
      }
      const target = this.findAssistantByRunId(runId);
      if (!target) {
        return;
      }
      const models = useModelStore();
      target.batch.status = action === "approve" ? "approved" : "rejected";
      prepareMessageStreamingState(target.message);
      target.message.text = "";
      target.message.error = false;
      prepareReasoningDisplay(target.message, wantsReasoningOutput(models));
      sessions.saveSessions();
      this.abortController = new AbortController();
      this.running = true;
      sessions.setStatus(action === "approve" ? "正在执行工具" : "正在重新决策");
      try {
        if (action === "approve") {
          const response = await api.streamToolApproval(
            runId,
            normalizeApprovalMode(target.session.approvalMode),
            models.currentModelId,
            models.runtimeOptionsPayload(),
            this.abortController.signal
          );
          await this.processAgentStream(response, target.message, target.session);
        } else {
          const reply = await api.decideToolAction(
            action,
            runId,
            normalizeApprovalMode(target.session.approvalMode),
            models.currentModelId,
            models.runtimeOptionsPayload(),
            this.abortController.signal
          );
          this.applyAgentReply(reply, target.message, target.session, runId, "rejected");
        }
      } catch (error) {
        this.applyStreamError(error, target.message, sessions);
      } finally {
        this.abortController = null;
        this.running = false;
      }
    },
    applyAgentReply(reply, message, session, handledRunId = null, handledStatus = "approved") {
      const sessions = useSessionStore();
      message.thinking = false;
      message.streaming = false;
      message.completedAt = message.completedAt || Date.now();
      finishReasoningDisplay(message);
      message.activities = [...(message.activities || []), ...(reply.activities || [])];
      message.toolBatches = message.toolBatches || [];
      const executions = [...(reply.executions || [])];
      if (handledRunId && executions.length) {
        const handledBatch = message.toolBatches.find((batch) => batch.runId === handledRunId);
        if (handledBatch) {
          handledBatch.status = handledStatus;
          handledBatch.tools = executions.shift().tools || handledBatch.tools;
        }
      }
      executions.forEach((execution) => {
        message.toolBatches = mergeToolBatchesPreservingHistory(message.toolBatches, [execution]);
      });
      if (reply.status === "approval_required") {
        message.text = "需要确认后才能继续执行。";
        message.toolBatches.push({ runId: reply.runId, tools: reply.tools || [], status: "pending" });
        sessions.setStatus("等待工具确认");
      } else if (reply.status === "loop_limit_required") {
        message.text = "已达到当前循环上限，需要确认是否继续。";
        message.toolBatches.push({ runId: reply.runId, tools: reply.tools || [], status: "loop_limit", interaction: reply.interaction });
        this.agentInteraction = reply.interaction;
        sessions.setStatus("等待继续确认");
      } else if (reply.status === "interaction_required") {
        message.text = "需要你的输入后才能继续。";
        message.toolBatches.push({ runId: reply.runId, tools: reply.tools || [], status: "waiting_input", interaction: reply.interaction });
        this.agentInteraction = reply.interaction;
        sessions.setStatus("等待用户输入");
      } else {
        message.text = reply.content || "任务已完成。";
        sessions.setStatus("已完成");
      }
      if (reply.status === "completed") {
        applyTokenUsage(message, reply.tokenUsage);
      }
      finishMessageLatency(message, reply.latencyMs);
      touchSession(session, sessions);
    },
    openInteraction(interaction) {
      this.agentInteraction = interaction;
    },
    closeInteraction() {
      this.agentInteraction = null;
    },
    async submitAgentInteraction(runId, value) {
      const answer = String(value || "").trim();
      if (!answer || this.running) {
        return;
      }
      const sessions = useSessionStore();
      if (await contextCompactionInProgress()) {
        sessions.setStatus("上下文压缩中，暂不可继续发送");
        return;
      }
      const target = this.findAssistantByRunId(runId);
      if (!target) {
        return;
      }
      const models = useModelStore();
      const isLoopLimitConfirmation = target.batch.status === "loop_limit";
      target.batch.status = isLoopLimitConfirmation && answer === "停止" ? "rejected" : "approved";
      prepareMessageStreamingState(target.message);
      target.message.text = "";
      target.message.error = false;
      prepareReasoningDisplay(target.message, wantsReasoningOutput(models));
      this.closeInteraction();
      sessions.saveSessions();
      this.abortController = new AbortController();
      this.running = true;
      sessions.setStatus("正在继续处理");
      try {
        const response = await api.submitAgentInteraction(
          runId,
          answer,
          normalizeApprovalMode(target.session.approvalMode),
          models.currentModelId,
          models.runtimeOptionsPayload(),
          this.abortController.signal
        );
        await this.processAgentStream(response, target.message, target.session);
      } catch (error) {
        this.applyStreamError(error, target.message, sessions);
      } finally {
        this.abortController = null;
        this.running = false;
      }
    },
    applyStreamError(error, message, sessions) {
      if (error.name === "AbortError") {
        message.thinking = false;
        message.streaming = false;
        message.completedAt = message.completedAt || Date.now();
        finishReasoningDisplay(message);
        message.text = "已停止。";
        finishMessageLatency(message);
        sessions.saveSessions();
        sessions.setStatus("已停止");
        return;
      }
      message.thinking = false;
      message.streaming = false;
      message.completedAt = message.completedAt || Date.now();
      message.error = true;
      finishReasoningDisplay(message);
      message.text = error.message || "请求失败，请检查后端服务和模型配置。";
      finishMessageLatency(message);
      sessions.saveSessions();
      sessions.setStatus("请求失败");
    },
    openImagePreview(url) {
      this.imagePreviewUrl = url;
    },
    closeImagePreview() {
      this.imagePreviewUrl = "";
    }
  }
});
