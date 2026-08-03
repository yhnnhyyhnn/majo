package com.agent.coding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DeletedResponse {
    @JsonProperty("deleted") private boolean deleted;

    public DeletedResponse() {}
    public DeletedResponse(boolean deleted) { this.deleted = deleted; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public static DeletedResponse ok() { return new DeletedResponse(true); }
}
