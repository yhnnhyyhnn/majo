package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class AgentStatsSummary {

    @JsonProperty("total_active_sessions") private int totalActiveSessions;
    @JsonProperty("total_messages") private int totalMessages;
    @JsonProperty("total_user_messages") private int totalUserMessages;
    @JsonProperty("total_assistant_messages") private int totalAssistantMessages;
    @JsonProperty("total_prompt_tokens") private int totalPromptTokens;
    @JsonProperty("total_completion_tokens") private int totalCompletionTokens;
    @JsonProperty("total_llm_calls") private int totalLlmCalls;
    @JsonProperty("total_tool_calls") private int totalToolCalls;
    @JsonProperty("by_date") private List<DailyStats> byDate;
    @JsonProperty("channel_stats") private List<ChannelStats> channelStats;
    @JsonProperty("start_date") private String startDate;
    @JsonProperty("end_date") private String endDate;

    public AgentStatsSummary() {}

    // Getters and setters
    public int getTotalActiveSessions() { return totalActiveSessions; }
    public void setTotalActiveSessions(int totalActiveSessions) { this.totalActiveSessions = totalActiveSessions; }
    public int getTotalMessages() { return totalMessages; }
    public void setTotalMessages(int totalMessages) { this.totalMessages = totalMessages; }
    public int getTotalUserMessages() { return totalUserMessages; }
    public void setTotalUserMessages(int totalUserMessages) { this.totalUserMessages = totalUserMessages; }
    public int getTotalAssistantMessages() { return totalAssistantMessages; }
    public void setTotalAssistantMessages(int totalAssistantMessages) { this.totalAssistantMessages = totalAssistantMessages; }
    public int getTotalPromptTokens() { return totalPromptTokens; }
    public void setTotalPromptTokens(int totalPromptTokens) { this.totalPromptTokens = totalPromptTokens; }
    public int getTotalCompletionTokens() { return totalCompletionTokens; }
    public void setTotalCompletionTokens(int totalCompletionTokens) { this.totalCompletionTokens = totalCompletionTokens; }
    public int getTotalLlmCalls() { return totalLlmCalls; }
    public void setTotalLlmCalls(int totalLlmCalls) { this.totalLlmCalls = totalLlmCalls; }
    public int getTotalToolCalls() { return totalToolCalls; }
    public void setTotalToolCalls(int totalToolCalls) { this.totalToolCalls = totalToolCalls; }
    public List<DailyStats> getByDate() { return byDate; }
    public void setByDate(List<DailyStats> byDate) { this.byDate = byDate; }
    public List<ChannelStats> getChannelStats() { return channelStats; }
    public void setChannelStats(List<ChannelStats> channelStats) { this.channelStats = channelStats; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }

    public static class DailyStats {
        @JsonProperty("date") private String date;
        @JsonProperty("chats") private int chats;
        @JsonProperty("active_sessions") private int activeSessions;
        @JsonProperty("user_messages") private int userMessages;
        @JsonProperty("assistant_messages") private int assistantMessages;
        @JsonProperty("total_messages") private int totalMessages;
        @JsonProperty("prompt_tokens") private int promptTokens;
        @JsonProperty("completion_tokens") private int completionTokens;
        @JsonProperty("llm_calls") private int llmCalls;
        @JsonProperty("tool_calls") private int toolCalls;

        public DailyStats() {}
        public DailyStats(String date) { this.date = date; }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public int getChats() { return chats; }
        public void setChats(int chats) { this.chats = chats; }
        public int getActiveSessions() { return activeSessions; }
        public void setActiveSessions(int activeSessions) { this.activeSessions = activeSessions; }
        public int getUserMessages() { return userMessages; }
        public void setUserMessages(int userMessages) { this.userMessages = userMessages; }
        public int getAssistantMessages() { return assistantMessages; }
        public void setAssistantMessages(int assistantMessages) { this.assistantMessages = assistantMessages; }
        public int getTotalMessages() { return totalMessages; }
        public void setTotalMessages(int totalMessages) { this.totalMessages = totalMessages; }
        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
        public int getLlmCalls() { return llmCalls; }
        public void setLlmCalls(int llmCalls) { this.llmCalls = llmCalls; }
        public int getToolCalls() { return toolCalls; }
        public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }
    }

    public static class ChannelStats {
        @JsonProperty("channel") private String channel;
        @JsonProperty("session_count") private int sessionCount;
        @JsonProperty("user_messages") private int userMessages;
        @JsonProperty("assistant_messages") private int assistantMessages;
        @JsonProperty("total_messages") private int totalMessages;

        public ChannelStats() {}
        public ChannelStats(String channel) { this.channel = channel; }

        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public int getSessionCount() { return sessionCount; }
        public void setSessionCount(int sessionCount) { this.sessionCount = sessionCount; }
        public int getUserMessages() { return userMessages; }
        public void setUserMessages(int userMessages) { this.userMessages = userMessages; }
        public int getAssistantMessages() { return assistantMessages; }
        public void setAssistantMessages(int assistantMessages) { this.assistantMessages = assistantMessages; }
        public int getTotalMessages() { return totalMessages; }
        public void setTotalMessages(int totalMessages) { this.totalMessages = totalMessages; }
    }
}
