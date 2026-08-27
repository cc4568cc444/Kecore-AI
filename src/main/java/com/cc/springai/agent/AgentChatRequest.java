package com.cc.springai.agent;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public class AgentChatRequest {
    private String prompt;
    private String conversationId;
    private String workingDirectory;
    private String approvalMode;
    private Boolean sandboxEnabled;
    private Long lightCompactedBefore;
    private String modelId;
    private String reasoningEffort;
    private String thinkingType;
    private String extraBody;
    private Boolean regenerate;
    private List<MultipartFile> files;

    public String prompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String conversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String workingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String approvalMode() {
        return approvalMode;
    }

    public void setApprovalMode(String approvalMode) {
        this.approvalMode = approvalMode;
    }

    public Boolean sandboxEnabled() {
        return sandboxEnabled;
    }

    public void setSandboxEnabled(Boolean sandboxEnabled) {
        this.sandboxEnabled = sandboxEnabled;
    }

    public Long lightCompactedBefore() {
        return lightCompactedBefore;
    }

    public void setLightCompactedBefore(Long lightCompactedBefore) {
        this.lightCompactedBefore = lightCompactedBefore;
    }

    public String modelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public String thinkingType() {
        return thinkingType;
    }

    public void setThinkingType(String thinkingType) {
        this.thinkingType = thinkingType;
    }

    public String extraBody() {
        return extraBody;
    }

    public void setExtraBody(String extraBody) {
        this.extraBody = extraBody;
    }

    public Boolean regenerate() {
        return regenerate;
    }

    public void setRegenerate(Boolean regenerate) {
        this.regenerate = regenerate;
    }

    public List<MultipartFile> files() {
        return files;
    }

    public void setFiles(List<MultipartFile> files) {
        this.files = files;
    }
}
