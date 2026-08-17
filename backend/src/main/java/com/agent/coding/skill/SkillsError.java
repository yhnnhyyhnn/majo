package com.agent.coding.skill;

/**
 * Skill-domain exceptions.exceptions.
 */
public class SkillsError extends RuntimeException {
    public SkillsError(String message) { super(message); }
    public SkillsError(String message, Throwable cause) { super(message, cause); }
}
