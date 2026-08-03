package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PushMessagesResponse {
    @JsonProperty("messages") private Object messages;
    @JsonProperty("pending_approvals") private Object pendingApprovals;

    public PushMessagesResponse() { this.messages = java.util.List.of(); this.pendingApprovals = java.util.List.of(); }
    public PushMessagesResponse(Object messages, Object pendingApprovals) {
        this.messages = messages; this.pendingApprovals = pendingApprovals;
    }
    public Object getMessages() { return messages; }
    public void setMessages(Object messages) { this.messages = messages; }
    public Object getPendingApprovals() { return pendingApprovals; }
    public void setPendingApprovals(Object pendingApprovals) { this.pendingApprovals = pendingApprovals; }
}
