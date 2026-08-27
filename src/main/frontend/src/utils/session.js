import {
  DEFAULT_AGENT_WORKING_DIRECTORY,
  DEFAULT_FINANCE_RETRIEVAL_STRATEGY,
  DEFAULT_PAPER_RETRIEVAL_STRATEGY,
  DEFAULT_LIGHT_COMPACTION_HOURS,
  MODES
} from "../services/config";

export function createId() {
  return window.crypto?.randomUUID?.() || `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function normalizeApprovalMode(value) {
  return ["work-auto", "default", "auto"].includes(value) ? value : "work-auto";
}

export function normalizeSandboxEnabled(value, approvalMode = "work-auto") {
  if (normalizeApprovalMode(approvalMode) === "auto") {
    return false;
  }
  return value !== false;
}

export function normalizeReasoningEffort(value) {
  const text = String(value || "").trim();
  return ["low", "medium", "high", "xhigh", "max"].includes(text) ? text : "";
}

export function normalizeThinkingType(value) {
  const text = String(value || "").trim();
  return text === "enabled" || text === "disabled" ? text : "";
}

export function normalizeLightCompactionHours(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return DEFAULT_LIGHT_COMPACTION_HOURS;
  }
  return Math.min(168, Math.max(0.1, number));
}

export function normalizedTimestamp(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : null;
}

export function createSession(mode) {
  const now = Date.now();
  return {
    id: createId(),
    mode,
    title: MODES[mode]?.sessionTitle || MODES.chat.sessionTitle,
    messages: [],
    workingDirectory: mode === "agent" ? DEFAULT_AGENT_WORKING_DIRECTORY : "",
    approvalMode: "work-auto",
    sandboxEnabled: true,
    runtimeReasoningEffort: "",
    runtimeThinkingType: "",
    financeRetrievalStrategy: DEFAULT_FINANCE_RETRIEVAL_STRATEGY,
    paperRetrievalStrategy: DEFAULT_PAPER_RETRIEVAL_STRATEGY,
    lightCompactionEnabled: true,
    lightCompactionHours: DEFAULT_LIGHT_COMPACTION_HOURS,
    lastAgentSentAt: null,
    lastLightCompactionEligibleAt: null,
    lastLightCompactedAt: null,
    lastLightCompactionCheckpointAt: null,
    contextUsage: null,
    mood: -60,
    gameState: "生气中",
    createdAt: now,
    updatedAt: now
  };
}

export function normalizeSession(session) {
  const mode = MODES[session?.mode] ? session.mode : "chat";
  return {
    ...createSession(mode),
    ...session,
    id: String(session?.id || createId()),
    mode,
    title: String(session?.title || MODES[mode].sessionTitle),
    workingDirectory: mode === "agent"
      ? String(session?.workingDirectory || DEFAULT_AGENT_WORKING_DIRECTORY)
      : String(session?.workingDirectory || ""),
    approvalMode: normalizeApprovalMode(session?.approvalMode),
    sandboxEnabled: normalizeSandboxEnabled(session?.sandboxEnabled, session?.approvalMode),
    runtimeReasoningEffort: normalizeReasoningEffort(session?.runtimeReasoningEffort),
    runtimeThinkingType: normalizeThinkingType(session?.runtimeThinkingType),
    financeRetrievalStrategy: String(session?.financeRetrievalStrategy || DEFAULT_FINANCE_RETRIEVAL_STRATEGY),
    paperRetrievalStrategy: String(session?.paperRetrievalStrategy || DEFAULT_PAPER_RETRIEVAL_STRATEGY),
    lightCompactionEnabled: session?.lightCompactionEnabled !== false,
    lightCompactionHours: normalizeLightCompactionHours(session?.lightCompactionHours),
    lastAgentSentAt: normalizedTimestamp(session?.lastAgentSentAt || session?.lastPromptSentAt),
    lastLightCompactionEligibleAt: normalizedTimestamp(session?.lastLightCompactionEligibleAt),
    lastLightCompactedAt: normalizedTimestamp(session?.lastLightCompactedAt),
    lastLightCompactionCheckpointAt: normalizedTimestamp(session?.lastLightCompactionCheckpointAt),
    createdAt: normalizedTimestamp(session?.createdAt) || Date.now(),
    updatedAt: normalizedTimestamp(session?.updatedAt) || Date.now(),
    messages: Array.isArray(session?.messages)
      ? session.messages.map(normalizeMessage)
      : []
  };
}

export function normalizeActiveSessionIds(value) {
  return {
    chat: value?.chat || null,
    finance: value?.finance || null,
    paper: value?.paper || null,
    game: value?.game || null,
    agent: value?.agent || null
  };
}

export function normalizeMessage(message) {
  return {
    id: message?.id || createId(),
    role: message?.role || "assistant",
    text: String(message?.text || ""),
    attachments: Array.isArray(message?.attachments) ? message.attachments : [],
    thinking: Boolean(message?.thinking),
    streaming: Boolean(message?.streaming),
    error: Boolean(message?.error),
    reasoning: typeof message?.reasoning === "string" ? message.reasoning : "",
    reasoningOpen: Boolean(message?.reasoningOpen),
    reasoningPending: Boolean(message?.reasoningPending),
    activities: Array.isArray(message?.activities) ? message.activities : [],
    toolBatches: Array.isArray(message?.toolBatches) ? message.toolBatches : [],
    taskGroups: Array.isArray(message?.taskGroups) ? message.taskGroups : [],
    agentRoundProgress: message?.agentRoundProgress || null,
    mode: String(message?.mode || ""),
    tokenUsage: normalizeTokenUsage(message?.tokenUsage),
    latencyMs: validLatencyMs(message?.latencyMs),
    retrievalMs: validLatencyMs(message?.retrievalMs),
    translationMs: validLatencyMs(message?.translationMs),
    modelResponseMs: validLatencyMs(message?.modelResponseMs),
    retrievalQuery: message?.retrievalQuery || "",
    createdAt: normalizedTimestamp(message?.createdAt) || Date.now(),
    startedAt: normalizedTimestamp(message?.startedAt),
    completedAt: normalizedTimestamp(message?.completedAt),
    firstTokenAt: normalizedTimestamp(message?.firstTokenAt),
    lastTokenAt: normalizedTimestamp(message?.lastTokenAt),
    inputTokenEstimate: validTokenCount(message?.inputTokenEstimate),
    outputTokenEstimate: validTokenCount(message?.outputTokenEstimate)
  };
}

export function userMessage(text, attachments = []) {
  return normalizeMessage({
    role: "user",
    text,
    attachments,
    createdAt: Date.now()
  });
}

export function assistantMessage() {
  return normalizeMessage({
    role: "assistant",
    thinking: true,
    startedAt: Date.now(),
    createdAt: Date.now()
  });
}

export function serializableSessions(sessions) {
  return sessions.map((session) => ({
    ...session,
    messages: (session.messages || []).map((message) => ({
      ...message,
      thinking: false,
      streaming: false
    }))
  }));
}

export function validLatencyMs(value) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : null;
}

export function validTokenCount(value) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? Math.round(number) : null;
}

export function normalizeTokenUsage(value) {
  if (!value || typeof value !== "object") {
    return null;
  }
  let totalTokens = validTokenCount(value.totalTokens ?? value.total_tokens);
  const outputTokens = validTokenCount(value.outputTokens ?? value.completionTokens ?? value.completion_tokens);
  const inputTokens = validTokenCount(value.inputTokens ?? value.promptTokens ?? value.prompt_tokens);
  if (totalTokens === null && outputTokens !== null && inputTokens !== null) {
    totalTokens = outputTokens + inputTokens;
  }
  if (totalTokens === null && outputTokens === null && inputTokens === null) {
    return null;
  }
  return { totalTokens, outputTokens, inputTokens };
}

export function applyTokenUsage(message, tokenUsage) {
  const normalized = normalizeTokenUsage(tokenUsage);
  if (normalized) {
    message.tokenUsage = normalized;
  }
}

export function finishMessageLatency(message, serverLatencyMs = null) {
  const startedAt = Number(message.startedAt || 0);
  const clientLatency = startedAt > 0 ? Date.now() - startedAt : null;
  const serverLatency = validLatencyMs(serverLatencyMs);
  const latency = validLatencyMs(clientLatency) ?? serverLatency;
  if (latency !== null) {
    message.latencyMs = latency;
    if (serverLatency !== null) {
      message.serverLatencyMs = serverLatency;
    }
  }
}

export function prepareReasoningDisplay(message, wantsReasoning) {
  if (!message || !wantsReasoning) {
    return;
  }
  if (typeof message.reasoning !== "string") {
    message.reasoning = "";
  }
  message.reasoningPending = true;
  if (typeof message.reasoningOpen !== "boolean") {
    message.reasoningOpen = false;
  }
}

export function finishReasoningDisplay(message) {
  if (message) {
    message.reasoningPending = false;
  }
}

export function appendReasoning(message, content) {
  if (!content) {
    return;
  }
  if (typeof message.reasoning !== "string") {
    message.reasoning = "";
  }
  message.reasoning += content;
  message.reasoningPending = false;
}

export function markTokenOutput(message, content) {
  if (!message || !content) {
    return;
  }
  if (!message.firstTokenAt) {
    message.firstTokenAt = Date.now();
  }
  message.lastTokenAt = Date.now();
  message.outputTokenEstimate = (message.outputTokenEstimate || 0) + estimateTokensFromText(content);
}

export function estimateTokensFromText(text) {
  const value = String(text || "");
  if (!value) {
    return 0;
  }
  const cjk = (value.match(/[\u4e00-\u9fff]/g) || []).length;
  const other = Math.max(0, value.length - cjk);
  return Math.max(1, Math.round(cjk * 0.75 + other / 4));
}
