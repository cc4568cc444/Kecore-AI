export function parseToolArguments(argumentsText) {
  if (!argumentsText) {
    return {};
  }
  try {
    return typeof argumentsText === "string" ? JSON.parse(argumentsText) : argumentsText;
  } catch (error) {
    return {};
  }
}

export function renderToolOutputText(value) {
  if (value === null || value === undefined) {
    return "";
  }
  const decodeControlEscapes = (content) => String(content || "")
    .replace(/\\r\\n/g, "\n")
    .replace(/\\n/g, "\n")
    .replace(/\\r/g, "\n")
    .replace(/\\t/g, "\t");
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      try {
        const parsed = JSON.parse(trimmed);
        if (typeof parsed === "string") {
          return parsed;
        }
      } catch {
        return decodeControlEscapes(trimmed.slice(1, -1).replace(/\\"/g, "\""));
      }
    }
    return decodeControlEscapes(value);
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch (error) {
    return String(value);
  }
}

export function taskRoleLabel(role) {
  const labels = {
    explorer: "探索",
    coder: "实现",
    reviewer: "审查",
    tester: "测试"
  };
  return labels[role] || role || "子任务";
}

export function toolBatchStatusText(status) {
  return status === "approved"
    ? "已确认并执行"
    : status === "executed"
      ? "已执行"
      : status === "rejected"
        ? "已拒绝"
        : status === "loop_limit"
          ? "等待继续确认"
          : status === "waiting_input"
            ? "等待用户输入"
            : status === "running"
              ? "子 agent 执行中"
              : "等待确认";
}

export function taskStatusLabel(batchStatus, output) {
  if (batchStatus === "running") {
    return "执行中";
  }
  if (batchStatus === "rejected") {
    return "已拒绝";
  }
  const text = renderToolOutputText(output);
  if (/失败|error|failed/i.test(text)) {
    return "失败";
  }
  if (text) {
    return "完成";
  }
  return toolBatchStatusText(batchStatus);
}

export function parseTaskOutput(output) {
  const text = renderToolOutputText(output);
  const result = { raw: text, summary: "", tools: "", taskId: "", taskGroupId: "", role: "", mode: "", maxRounds: "" };
  if (!text) {
    return result;
  }
  const taskId = text.match(/taskId[:：]\s*([A-Za-z0-9_-]+)/);
  const taskGroupId = text.match(/taskGroupId[:：]\s*([A-Za-z0-9_-]+)/);
  if (taskId) {
    result.taskId = taskId[1];
  }
  if (taskGroupId) {
    result.taskGroupId = taskGroupId[1];
  }
  if (text.includes("子 agent 部分总结：")) {
    const [, rest = ""] = text.split("子 agent 部分总结：");
    const [summary = "", tools = ""] = rest.split("已执行工具：");
    result.summary = summary.trim();
    result.tools = tools.trim();
  } else if (text.includes("子 agent 总结：")) {
    const [, rest = ""] = text.split("子 agent 总结：");
    const [summary = "", tools = ""] = rest.split("已执行工具：");
    result.summary = summary.trim();
    result.tools = tools.trim();
  } else if (text.includes("result:")) {
    const [, rest = ""] = text.split("result:");
    result.summary = rest.trim();
  } else {
    result.summary = text;
  }
  return result;
}

function taskIdFromTool(tool) {
  if (!tool || tool.name !== "task") {
    return "";
  }
  const args = parseToolArguments(tool.arguments);
  if (args.taskId) {
    return String(args.taskId);
  }
  return parseTaskOutput(tool.output).taskId || "";
}

function mergeToolBatchesWithExistingProgress(existingBatches, executionBatches) {
  const progressByTask = new Map();
  const fallbackProgress = [];
  (existingBatches || []).forEach((batch) => {
    (batch.tools || []).forEach((tool) => {
      const taskId = taskIdFromTool(tool);
      if (taskId && tool.progress?.length) {
        progressByTask.set(taskId, tool.progress);
      } else if (tool.name === "task" && tool.progress?.length) {
        fallbackProgress.push(tool.progress);
      }
    });
  });
  let fallbackIndex = 0;
  return (executionBatches || []).map((execution) => ({
    ...execution,
    tools: (execution.tools || []).map((tool) => {
      const taskId = taskIdFromTool(tool);
      if (taskId && progressByTask.has(taskId)) {
        return { ...tool, progress: progressByTask.get(taskId) };
      }
      if (tool.name === "task" && !taskId && fallbackProgress[fallbackIndex]) {
        return { ...tool, progress: fallbackProgress[fallbackIndex++] };
      }
      return tool;
    }),
    status: execution.status || "executed"
  }));
}

export function toolExecutionKey(tool) {
  return `${tool.name || ""}|${tool.arguments || ""}|${renderToolOutputText(tool.output)}`;
}

export function mergeToolBatchesPreservingHistory(existingBatches = [], executionBatches = []) {
  const mergedExecutions = mergeToolBatchesWithExistingProgress(existingBatches, executionBatches);
  const result = [...existingBatches];
  for (const execution of mergedExecutions) {
    const runId = execution.runId || "";
    const existing = runId ? result.find((batch) => batch.runId === runId) : null;
    if (existing) {
      existing.tools = execution.tools || existing.tools || [];
      existing.status = execution.status || existing.status || "executed";
    } else {
      const keys = new Set((execution.tools || []).map(toolExecutionKey));
      const duplicateIndex = result.findIndex((batch) => {
        const batchKeys = new Set((batch.tools || []).map(toolExecutionKey));
        return keys.size > 0 && keys.size === batchKeys.size && [...keys].every((key) => batchKeys.has(key));
      });
      if (duplicateIndex >= 0) {
        result[duplicateIndex] = { ...result[duplicateIndex], ...execution, status: execution.status || "executed" };
      } else {
        result.push({ ...execution, status: execution.status || "executed" });
      }
    }
  }
  return result;
}

export function applyTaskProgress(message, data) {
  const taskId = data?.taskId || data?.id;
  if (!taskId) {
    return;
  }
  for (const batch of message.toolBatches || []) {
    for (const tool of batch.tools || []) {
      if (tool.name === "task" && taskIdFromTool(tool) === taskId) {
        tool.progress = [...(tool.progress || []), data];
        batch.status = data.status === "completed" ? "executed" : "running";
      }
    }
  }
}

export function applyAgentRoundProgress(message, data) {
  const limit = Number(data?.toolRoundLimit || data?.maxRounds || 0);
  if (!Number.isFinite(limit) || limit <= 0) {
    message.agentRoundProgress = {
      ...(message.agentRoundProgress || {}),
      ...data
    };
    return;
  }
  const completedRounds = Math.max(0, Math.min(Number(data.completedRounds || 0), limit));
  const currentRound = Math.max(1, Math.min(Number(data.currentRound || completedRounds + 1), limit));
  const percent = Math.max(0, Math.min(Number(data.percent ?? ((completedRounds / limit) * 100)), 100));
  message.agentRoundProgress = {
    ...(message.agentRoundProgress || {}),
    ...data,
    currentRound,
    completedRounds,
    toolRoundLimit: limit,
    hardToolRoundLimit: Number(data.hardToolRoundLimit || 0),
    percent,
    status: data.status || "running",
    updatedAt: Date.now()
  };
}

export function agentRoundProgressStatusText(status) {
  const labels = {
    tool_calling: "正在执行工具",
    tool_completed: "本轮工具已完成",
    waiting_approval: "等待工具确认",
    waiting_input: "等待用户输入",
    continued: "已继续执行",
    limit_reached: "达到当前上限",
    completed: "已完成",
    failed: "执行失败",
    waiting: "等待继续"
  };
  return labels[status] || "执行中";
}
