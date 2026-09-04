export const STORAGE_KEY = "spring-ai-chat-sessions";
export const ACTIVE_SESSION_KEY = "spring-ai-active-sessions";
export const MODE_KEY = "spring-ai-mode";
export const MODEL_KEY = "spring-ai-active-model";

export const DEFAULT_AGENT_WORKING_DIRECTORY = "D:\\AgentWorkshop";
export const DEFAULT_LIGHT_COMPACTION_HOURS = 6;
export const DEFAULT_FINANCE_RETRIEVAL_STRATEGY = "default";
export const DEFAULT_PAPER_RETRIEVAL_STRATEGY = "parent-child";

export const MODES = {
  chat: {
    title: "Kecore AI",
    emptyTitle: "有什么可以帮忙的？",
    placeholder: "向 Kecore AI 提问",
    sessionTitle: "新对话",
    generating: "正在生成"
  },
  finance: {
    title: "多文档金融研究",
    emptyTitle: "跨公司、跨年度年报分析",
    placeholder: "询问财务指标、年度趋势或同行公司对比",
    sessionTitle: "金融问答",
    generating: "正在检索年报并生成答案"
  },
  paper: {
    title: "科研论文问答",
    emptyTitle: "从论文证据中找到准确答案",
    placeholder: "询问论文方法、实验指标、数据集或结论",
    sessionTitle: "论文问答",
    generating: "正在检索论文并核对证据"
  },
  agent: {
    title: "Agent",
    emptyTitle: "需要推进什么任务？",
    placeholder: "描述目标、约束或需要解决的问题",
    sessionTitle: "新任务",
    generating: "正在处理"
  }
};

export const API_ENDPOINTS = Object.freeze({
  sessions: "/api/sessions",
  defaultWorkingDirectory: "/api/sessions/default-working-directory",
  agentContext: "/agent/context",
  agentContextPreview: "/agent/context/preview",
  agentContextCompact: "/agent/context/compact",
  agentContextLightCompact: "/agent/context/light-compact",
  agentApprovalMode: "/agent/approval-mode",
  agentTasksProgress: "/agent/tasks/progress",
  agentTasksGroups: "/agent/tasks/groups",
  memory: "/api/memory",
  skills: "/skills",
  skillsRender: "/skills/render",
  skillsFile: "/skills/file",
  fileOpen: "/api/files/open",
  mcpServers: "/api/mcp/servers",
  models: "/api/models",
  model: (modelId) => `/api/models/${encodeURIComponent(modelId)}`,
  ragDocuments: "/rag/documents",
  ragUpload: "/rag/upload",
  chatStream: "/ai/chat/stream",
  gameChatStream: "/game/chat/stream",
  agentChatStream: "/agent/chat/stream",
  financeChatStream: "/finance/chat/stream",
  paperChatStream: "/paper/chat/stream",
  agentInteractStream: "/agent/interact/stream",
  agentApproveStream: "/agent/approve/stream",
  session: (sessionId) => `/api/sessions/${encodeURIComponent(sessionId)}`,
  agentAction: (action) => `/agent/${action}`
});

export function visibleMode(value) {
  const requestedMode = String(value || "chat");
  const mode = requestedMode === "paper" || requestedMode === "game" ? "finance" : requestedMode;
  return Object.prototype.hasOwnProperty.call(MODES, mode) ? mode : "chat";
}
