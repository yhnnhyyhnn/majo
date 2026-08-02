package com.agent.coding.skill;

/** Raised for request-validation failures that should surface as 422
 * (mirrors FastAPI's HTTPException(status_code=422) usage, e.g. tag limits). */
public class SkillTagLimitError extends SkillsError {
    public SkillTagLimitError(String message) { super(message); }
}
