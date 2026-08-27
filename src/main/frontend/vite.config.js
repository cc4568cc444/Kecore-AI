import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const springBootTarget = process.env.VITE_SPRING_BOOT_TARGET || "http://localhost:8088";
const frontendRoot = path.dirname(fileURLToPath(import.meta.url));

const localStaticFiles = new Map([
  ["/webjars/katex/0.16.44/dist/katex.min.js", path.resolve(frontendRoot, "node_modules/katex/dist/katex.min.js"), "application/javascript; charset=utf-8"],
  ["/webjars/katex/0.16.44/dist/katex.min.css", path.resolve(frontendRoot, "node_modules/katex/dist/katex.min.css"), "text/css; charset=utf-8"]
].flatMap(([requestPath, filePath, contentType]) => [
  [requestPath, { filePath, contentType }],
  [`/vue${requestPath}`, { filePath, contentType }]
]));

function legacyStaticFallbackPlugin() {
  return {
    name: "springai-legacy-static-fallback",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const requestUrl = req.url ? req.url.split("?")[0] : "";
        const match = localStaticFiles.get(requestUrl);
        if (!match) {
          next();
          return;
        }

        if (!fs.existsSync(match.filePath)) {
          res.statusCode = 404;
          res.end(`Not found: ${requestUrl}`);
          return;
        }

        res.setHeader("Content-Type", match.contentType);
        fs.createReadStream(match.filePath).pipe(res);
      });
    }
  };
}

export default defineConfig({
  base: "/vue/",
  plugins: [legacyStaticFallbackPlugin(), vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": springBootTarget,
      "/agent": springBootTarget,
      "/ai": springBootTarget,
      "/finance": springBootTarget,
      "/game": springBootTarget,
      "/rag": springBootTarget,
      "/skills": springBootTarget
    }
  },
  build: {
    outDir: "../resources/static/vue",
    emptyOutDir: true
  }
});
