import { defineStore } from "pinia";
import { api } from "../services/api";
import { useSessionStore } from "./session";

export const useKnowledgeStore = defineStore("knowledge", {
  state: () => ({
    open: false,
    sessionId: "",
    documents: [],
    status: "选择 PDF 后上传到当前会话知识库。",
    busy: false
  }),
  getters: {
    sessionTitle() {
      const session = useSessionStore().sessions.find((item) => item.id === this.sessionId);
      return session?.title || "当前会话";
    }
  },
  actions: {
    async openForSession(sessionId) {
      this.sessionId = sessionId || useSessionStore().activeSession?.id || "";
      this.open = true;
      await this.loadDocuments();
    },
    close() {
      this.open = false;
    },
    async loadDocuments() {
      if (!this.sessionId) {
        return;
      }
      this.busy = true;
      try {
        this.documents = await api.loadKnowledgeDocuments(this.sessionId);
        this.status = `已加载 ${this.documents.length} 个文档。`;
      } catch (error) {
        this.status = error.message || "知识库文档加载失败";
      } finally {
        this.busy = false;
      }
    },
    async upload(file) {
      if (!file || !this.sessionId) {
        return;
      }
      this.busy = true;
      try {
        await api.uploadKnowledgeFile(this.sessionId, file);
        this.status = "PDF 已上传";
        await this.loadDocuments();
      } catch (error) {
        this.status = error.message || "PDF 上传失败";
      } finally {
        this.busy = false;
      }
    },
    async deleteDocument(documentId) {
      if (!documentId || !this.sessionId) {
        return;
      }
      this.busy = true;
      try {
        await api.deleteKnowledgeDocument(this.sessionId, documentId);
        this.status = "文档已删除";
        await this.loadDocuments();
      } catch (error) {
        this.status = error.message || "文档删除失败";
      } finally {
        this.busy = false;
      }
    }
  }
});
