import { defineStore } from "pinia";
import { useSessionStore } from "./session";

export const useShellStore = defineStore("shell", {
  getters: {
    session: () => useSessionStore(),
    title: () => useSessionStore().title,
    placeholder: () => useSessionStore().placeholder,
    emptyTitle: () => useSessionStore().emptyTitle,
    status: () => useSessionStore().status,
    mode: () => useSessionStore().mode
  },
  actions: {
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
