package com.agent.coding.skill;

/** Raised when a skill operation collides with an existing skill. */
public class SkillConflictError extends SkillsError {
    public SkillConflictError(String message) { super(message); }
}
