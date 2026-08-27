import katex from "katex";

const AUTO_LINK_URL_PATTERN = /https?:\/\/[^\s<>"'`]+/gi;
const LOCAL_FILE_PATH_PATTERN = /\b[A-Za-z]:\\(?:[^\\/:*?"<>|\r\n]+\\)*[^\\/:*?"<>|\r\n]*\.[A-Za-z0-9]{1,16}/g;
const IMAGE_URL_PATTERN = /\.(?:png|jpe?g|gif|webp|bmp|svg|avif)$/i;

export function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function isImageUrl(url) {
  return IMAGE_URL_PATTERN.test(String(url).split("?")[0] || "");
}

function localFileUrl(path) {
  return `file:///${String(path).replace(/\\/g, "/")}`;
}

function stashHtml(html, stash) {
  const key = `\u0000HTML_${stash.length}\u0000`;
  stash.push(html);
  return key;
}

function renderMathFormula(source, displayMode) {
  try {
    return katex.renderToString(source, {
      displayMode,
      throwOnError: false,
      strict: "ignore",
      trust: false
    });
  } catch (error) {
    return `<code>${escapeHtml(source)}</code>`;
  }
}

function renderInline(value) {
  const stash = [];
  let source = String(value ?? "");
  source = source.replace(/\$\$([\s\S]+?)\$\$/g, (_match, formula) =>
    stashHtml(renderMathFormula(formula, true), stash));
  source = source.replace(/\\\[([\s\S]+?)\\\]/g, (_match, formula) =>
    stashHtml(renderMathFormula(formula, true), stash));
  source = source.replace(/\\\(([\s\S]+?)\\\)/g, (_match, formula) =>
    stashHtml(renderMathFormula(formula, false), stash));
  source = source.replace(/\$(?!\$)([^$\n]+?)\$/g, (_match, formula) =>
    stashHtml(renderMathFormula(formula, false), stash));
  let html = escapeHtml(source);
  html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
  html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_match, label, url) => {
    const safeUrl = escapeHtml(url);
    const attrs = isImageUrl(url) ? ` data-image-url="${safeUrl}"` : "";
    return stashHtml(`<a href="${safeUrl}" target="_blank" rel="noopener noreferrer"${attrs}>${escapeHtml(label)}</a>`, stash);
  });
  html = html.replace(AUTO_LINK_URL_PATTERN, (url) => {
    const safeUrl = escapeHtml(url);
    const attrs = isImageUrl(url) ? ` data-image-url="${safeUrl}"` : "";
    return stashHtml(`<a href="${safeUrl}" target="_blank" rel="noopener noreferrer"${attrs}>${safeUrl}</a>`, stash);
  });
  html = html.replace(LOCAL_FILE_PATH_PATTERN, (path) => {
    const safePath = escapeHtml(path);
    return stashHtml(`<a href="${escapeHtml(localFileUrl(path))}" class="local-file-link" data-local-file-path="${safePath}">${safePath}</a>`, stash);
  });
  return html.replace(/\u0000HTML_(\d+)\u0000/g, (_match, index) => stash[Number(index)] || "");
}

export function renderMarkdown(value) {
  const source = String(value || "");
  if (!source.trim()) {
    return "";
  }

  const blocks = [];
  let inCode = false;
  let codeLang = "";
  let codeLines = [];
  let paragraph = [];
  let listItems = [];

  const flushParagraph = () => {
    if (paragraph.length) {
      blocks.push(`<p>${renderInline(paragraph.join("\n"))}</p>`);
      paragraph = [];
    }
  };
  const flushList = () => {
    if (listItems.length) {
      blocks.push(`<ul>${listItems.map((item) => `<li>${renderInline(item)}</li>`).join("")}</ul>`);
      listItems = [];
    }
  };
  const flushCode = () => {
    blocks.push(`<pre><code${codeLang ? ` class="language-${escapeHtml(codeLang)}"` : ""}>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
    codeLines = [];
    codeLang = "";
  };

  for (const line of source.split(/\r?\n/)) {
    const fence = line.match(/^```([A-Za-z0-9_-]*)\s*$/);
    if (fence) {
      if (inCode) {
        flushCode();
        inCode = false;
      } else {
        flushParagraph();
        flushList();
        inCode = true;
        codeLang = fence[1] || "";
      }
      continue;
    }
    if (inCode) {
      codeLines.push(line);
      continue;
    }
    if (!line.trim()) {
      flushParagraph();
      flushList();
      continue;
    }
    const heading = line.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      flushParagraph();
      flushList();
      const level = heading[1].length;
      blocks.push(`<h${level}>${renderInline(heading[2])}</h${level}>`);
      continue;
    }
    const list = line.match(/^\s*[-*]\s+(.+)$/);
    if (list) {
      flushParagraph();
      listItems.push(list[1]);
      continue;
    }
    paragraph.push(line);
  }

  if (inCode) {
    flushCode();
  }
  flushParagraph();
  flushList();

  return blocks.join("\n");
}

export function plainTextPreview(value, length = 80) {
  const text = String(value || "").replace(/\s+/g, " ").trim();
  return text.length > length ? `${text.slice(0, length)}...` : text;
}

export function tailTextPreview(value, length = 96) {
  const text = String(value || "").replace(/\s+/g, " ").trim();
  if (text.length <= length) {
    return text;
  }
  const segments = text.split(/(?<=[。！？!?\.])\s+/).filter(Boolean);
  const tailSegments = segments.length > 1 ? segments.slice(-2).join(" ") : "";
  if (tailSegments && tailSegments.length <= length + 24) {
    return `...${tailSegments}`;
  }
  return `...${text.slice(-length)}`;
}
