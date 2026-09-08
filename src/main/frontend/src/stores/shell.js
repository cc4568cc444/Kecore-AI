import { defineStore } from "pinia";
import { useSessionStore } from "./session";

export const useShellStore = defineStore("shell", {
  state: () => ({ sidebarOpen: typeof window === "undefined" || window.innerWidth > 900 }),
  getters: {
    session: () => useSessionStore(),
    title: () => useSessionStore().title,
    placeholder: () => useSessionStore().placeholder,
    emptyTitle: () => useSessionStore().emptyTitle,
    status: () => useSessionStore().status,
    mode: () => useSessionStore().mode
  },
  actions: {
    toggleSidebar() { this.sidebarOpen = !this.sidebarOpen; },
    closeMobileSidebar() { if (window.innerWidth <= 900) this.sidebarOpen = false; },
    isModeActive(mode) {
      return useSessionStore().mode === mode;
    },
    changeMode(mode) {
      useSessionStore().changeMode(mode);
    }
  }
});

export function resolveInitialMode() {
  return useSessionStore().mode;
}
