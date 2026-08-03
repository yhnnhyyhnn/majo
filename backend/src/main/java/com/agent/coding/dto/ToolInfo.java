package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ToolInfo {
    @JsonProperty("name") private String name;
    @JsonProperty("description") private String description;

    public ToolInfo() {}
    public ToolInfo(String name, String description) { this.name = name; this.description = description; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
