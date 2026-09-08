import { createApp } from "vue";
import { createPinia } from "pinia";
import "./styles/app.css";
import "./styles/migration.css";
import "./styles/theme.css";
import "./styles/chat-layout.css";
import App from "./App.vue";
import { useModelStore } from "./stores/model";
import { useSessionStore } from "./stores/session";
import { useWorkspaceStore } from "./stores/workspace";
import { visibleMode, MODE_KEY } from "./services/config";

const THEME_KEY = "kecore-theme";
const savedTheme = localStorage.getItem(THEME_KEY);
const initialTheme = savedTheme === "dark" || savedTheme === "light"
  ? savedTheme
  : (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
document.documentElement.dataset.theme = initialTheme;
document.documentElement.style.colorScheme = initialTheme;

const initialMode = visibleMode(localStorage.getItem(MODE_KEY) || "chat");
document.documentElement.setAttribute("data-initial-mode", initialMode);
document.body.dataset.mode = initialMode;

const pinia = createPinia();
const app = createApp(App);
app.use(pinia);

const sessions = useSessionStore();
const models = useModelStore();
const workspace = useWorkspaceStore();

sessions.loadLocalSessions();
sessions.ensureSession(initialMode);
sessions.saveSessions();

app.mount("#app");

sessions.initialize();
models.loadModels();
workspace.loadMcpServers();
