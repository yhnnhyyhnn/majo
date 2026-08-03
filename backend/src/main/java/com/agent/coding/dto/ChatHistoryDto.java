package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ChatHistoryDto {
    @JsonProperty("messages") private List<?> messages;
    @JsonProperty("status") private String status;

    public ChatHistoryDto() {}
    public ChatHistoryDto(List<?> messages, String status) { this.messages = messages; this.status = status; }
    public List<?> getMessages() { return messages; }
    public void setMessages(List<?> messages) { this.messages = messages; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
