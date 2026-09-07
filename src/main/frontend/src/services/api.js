import { API_ENDPOINTS } from "./config";

const JSON_HEADERS = {
  "Content-Type": "application/json",
  Accept: "application/json"
};

const ACCEPT_JSON = { Accept: "application/json" };

function query(params) {
  return new URLSearchParams(params).toString();
}

function requireOk(response, fallbackMessage = "HTTP") {
  if (!response.ok) {
    throw new Error(`${fallbackMessage} ${response.status}`);
  }
  return response;
}

async function requireOkPromise(responsePromise, fallbackMessage = "HTTP") {
  return requireOk(await responsePromise, fallbackMessage);
}

async function json(responsePromise, fallbackMessage) {
  const response = await responsePromise;
  if (!response.ok) {
    let message = "";
    try {
      const error = await response.json();
      message = error?.message || error?.error || "";
    } catch {
      // Keep the generic status message when the server did not return JSON.
    }
    throw new Error(message || `${fallbackMessage || "HTTP"} ${response.status}`);
  }
  return response.json();
}

export const api = Object.freeze({
  persistSessions(sessions) {
    return requireOkPromise(fetch(API_ENDPOINTS.sessions, {
      method: "PUT",
      headers: JSON_HEADERS,
      body: JSON.stringify(sessions)
    }), "HTTP");
  },

  listSessions() {
    return json(fetch(API_ENDPOINTS.sessions, { headers: ACCEPT_JSON }), "HTTP");
  },

  deleteSession(sessionId) {
    return requireOkPromise(fetch(API_ENDPOINTS.session(sessionId), { method: "DELETE" }), "HTTP");
  },

  defaultWorkingDirectory() {
    return json(fetch(API_ENDPOINTS.defaultWorkingDirectory, { headers: ACCEPT_JSON }), "HTTP");
  },

  listModels() {
    return json(fetch(API_ENDPOINTS.models, { headers: ACCEPT_JSON }), "HTTP");
  },

  exportModels() {
    return json(fetch(`${API_ENDPOINTS.models}/export`, { headers: ACCEPT_JSON }), "HTTP");
  },

  importModels(models) {
    return json(fetch(`${API_ENDPOINTS.models}/import`, {
      method: "PUT",
      headers: JSON_HEADERS,
      body: JSON.stringify(models)
    }), "HTTP");
  },

  saveModel(payload, originalId = "") {
    const method = originalId ? "PUT" : "POST";
    const endpoint = originalId ? API_ENDPOINTS.model(originalId) : API_ENDPOINTS.models;
    return json(fetch(endpoint, {
      method,
      headers: JSON_HEADERS,
      body: JSON.stringify(payload)
    }), "HTTP");
  },

  async deleteModel(modelId) {
    return requireOk(await fetch(API_ENDPOINTS.model(modelId), { method: "DELETE" }), "HTTP");
  },

  streamChat(params, signal) {
    return fetch(`${API_ENDPOINTS.chatStream}?${params.toString()}`, {
      signal,
      headers: { Accept: "text/event-stream" }
    });
  },

  streamFinanceChat(params, signal) {
    return fetch(`${API_ENDPOINTS.financeChatStream}?${params.toString()}`, {
      signal,
      headers: { Accept: "text/event-stream" }
    });
  },

  streamPaperChat(params, signal) {
    return fetch(`${API_ENDPOINTS.paperChatStream}?${params.toString()}`, {
      signal,
      headers: { Accept: "text/event-stream" }
    });
  },

  streamAgentChat(formData, signal) {
    return fetch(API_ENDPOINTS.agentChatStream, {
      method: "POST",
      signal,
      headers: { Accept: "text/event-stream" },
      body: formData
    });
  },

  updateAgentApprovalMode(conversationId, approvalMode, sandboxEnabled, workingDirectory) {
    return json(fetch(API_ENDPOINTS.agentApprovalMode, {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ conversationId, approvalMode, sandboxEnabled, workingDirectory })
    }), "HTTP");
  },

  streamToolApproval(runId, approvalMode, modelId, runtimeOptions, signal) {
    return fetch(API_ENDPOINTS.agentApproveStream, {
      method: "POST",
      signal,
      headers: {
        ...JSON_HEADERS,
        Accept: "text/event-stream"
      },
      body: JSON.stringify({ runId, approvalMode, modelId, ...(runtimeOptions || {}) })
    });
  },

  decideToolAction(action, runId, approvalMode, modelId, runtimeOptions, signal) {
    return json(fetch(API_ENDPOINTS.agentAction(action), {
      method: "POST",
      signal,
      headers: JSON_HEADERS,
      body: JSON.stringify({ runId, approvalMode, modelId, ...(runtimeOptions || {}) })
    }), "HTTP");
  },

  submitAgentInteraction(runId, value, approvalMode, modelId, runtimeOptions, signal) {
    return fetch(API_ENDPOINTS.agentInteractStream, {
      method: "POST",
      signal,
      headers: {
        ...JSON_HEADERS,
        Accept: "text/event-stream"
      },
      body: JSON.stringify({ runId, value, approvalMode, modelId, ...(runtimeOptions || {}) })
    });
  },

  loadAgentContext(params) {
    return json(fetch(`${API_ENDPOINTS.agentContext}?${query(params)}`, { headers: ACCEPT_JSON }), "HTTP");
  },

  loadAgentContextPreview(params) {
    return json(fetch(`${API_ENDPOINTS.agentContextPreview}?${query(params)}`, { headers: ACCEPT_JSON }), "HTTP");
  },

  compactAgentContext(payload) {
    return json(fetch(API_ENDPOINTS.agentContextCompact, {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify(payload)
    }), "HTTP");
  },

  lightCompactAgentContext(payload) {
    return json(fetch(API_ENDPOINTS.agentContextLightCompact, {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify(payload)
    }), "HTTP");
  },

  async taskProgress(conversationId) {
    const response = await fetch(`${API_ENDPOINTS.agentTasksProgress}?conversationId=${encodeURIComponent(conversationId)}`, {
      headers: ACCEPT_JSON
    });
    return response.ok ? response.json() : [];
  },

  async taskGroups(conversationId) {
    const response = await fetch(`${API_ENDPOINTS.agentTasksGroups}?conversationId=${encodeURIComponent(conversationId)}`, {
      headers: ACCEPT_JSON
    });
    return response.ok ? response.json() : [];
  },

  loadMemoryFiles(workingDirectory) {
    return json(fetch(`${API_ENDPOINTS.memory}?${query({ workingDirectory })}`, { headers: ACCEPT_JSON }), "HTTP");
  },

  saveMemoryFile(payload) {
    return json(fetch(API_ENDPOINTS.memory, {
      method: "PUT",
      headers: JSON_HEADERS,
      body: JSON.stringify(payload)
    }), "HTTP");
  },

  createMemory(payload) {
    return json(fetch(API_ENDPOINTS.memory, {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify(payload)
    }), "HTTP");
  },

  loadSkills(workingDirectory) {
    return json(fetch(`${API_ENDPOINTS.skills}?${query({ workingDirectory })}`, { headers: ACCEPT_JSON }), "HTTP");
  },

  renderSkill(workingDirectory, commandName, argumentsText) {
    return json(fetch(`${API_ENDPOINTS.skillsRender}?${query({
      workingDirectory,
      commandName,
      arguments: argumentsText || ""
    })}`, { headers: ACCEPT_JSON }), "HTTP");
  },

  loadSkillFile(workingDirectory, commandName) {
    return json(fetch(`${API_ENDPOINTS.skillsFile}?${query({ workingDirectory, commandName })}`, {
      headers: ACCEPT_JSON
    }), "HTTP");
  },

  saveSkillFile(payload) {
    return json(fetch(API_ENDPOINTS.skillsFile, {
      method: "PUT",
      headers: JSON_HEADERS,
      body: JSON.stringify(payload)
    }), "HTTP");
  },

  deleteSkill(workingDirectory, commandName) {
    return json(fetch(`${API_ENDPOINTS.skills}?${query({ workingDirectory, commandName })}`, {
      method: "DELETE",
      headers: ACCEPT_JSON
    }), "HTTP");
  },

  loadKnowledgeDocuments(conversationId) {
    return json(fetch(`${API_ENDPOINTS.ragDocuments}?${query({ conversationId })}`, {
      headers: ACCEPT_JSON
    }), "HTTP");
  },

  uploadKnowledgeFile(conversationId, file) {
    const formData = new FormData();
    formData.append("conversationId", conversationId);
    formData.append("file", file);
    return json(fetch(API_ENDPOINTS.ragUpload, {
      method: "POST",
      body: formData,
      headers: ACCEPT_JSON
    }), "HTTP");
  },

  async deleteKnowledgeDocument(conversationId, documentId) {
    return requireOk(await fetch(`${API_ENDPOINTS.ragDocuments}/${encodeURIComponent(documentId)}?${query({ conversationId })}`, {
      method: "DELETE"
    }), "HTTP");
  },

  loadMcpServers() {
    return json(fetch(API_ENDPOINTS.mcpServers, { headers: ACCEPT_JSON }), "HTTP");
  },

  async openLocalFile(path) {
    const result = await json(fetch(API_ENDPOINTS.fileOpen, {
      method: "POST",
      headers: JSON_HEADERS,
      body: JSON.stringify({ path })
    }), "HTTP");
    if (!result || result.success === false) {
      throw new Error(result?.message || "打开本地文件失败");
    }
    return result;
  }
});
