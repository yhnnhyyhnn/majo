package com.agent.coding.mcp;

/**
 * MCP-specific HTTP exception carrying an HTTP status and a {@code detail}
 * message, mirroring FastAPI's {@code HTTPException(detail=...)} that the
 * frontend parses from the {@code {"detail": ...}} error contract.
 */
public class McpException extends RuntimeException {

    private final int status;

    public McpException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
