package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class UploadLimitResponse {
    @JsonProperty("max_file_size_mb") private int maxFileSizeMb;
    @JsonProperty("allowed_types") private List<String> allowedTypes;

    public UploadLimitResponse() {}
    public UploadLimitResponse(int maxFileSizeMb, List<String> allowedTypes) {
        this.maxFileSizeMb = maxFileSizeMb; this.allowedTypes = allowedTypes;
    }
    public int getMaxFileSizeMb() { return maxFileSizeMb; }
    public void setMaxFileSizeMb(int maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }
    public List<String> getAllowedTypes() { return allowedTypes; }
    public void setAllowedTypes(List<String> allowedTypes) { this.allowedTypes = allowedTypes; }
}
