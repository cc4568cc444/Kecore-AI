export async function readSse(response, onEvent) {
  if (!response.ok || !response.body) {
    throw new Error(`请求失败：HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let currentEvent = "";

  const processLine = (line) => {
    if (line.startsWith("event:")) {
      currentEvent = line.substring(6).trim();
      return;
    }
    if (!line.startsWith("data:")) {
      return;
    }

    const dataText = line.substring(5).trim();
    if (!dataText) {
      return;
    }

    try {
      onEvent(currentEvent || "message", JSON.parse(dataText));
    } catch (error) {
      console.error("解析 SSE 数据失败:", error, dataText);
    } finally {
      currentEvent = "";
    }
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() || "";
    lines.forEach(processLine);
  }

  buffer += decoder.decode();
  if (buffer.trim()) {
    buffer.split("\n").forEach(processLine);
  }
}
