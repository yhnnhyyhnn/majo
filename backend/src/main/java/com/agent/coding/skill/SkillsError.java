package com.agent.coding.skill;

/**
 * Skill-domain exceptions mirroring qwenpaw.exceptions.
 */
public class SkillsError extends RuntimeException {
    public SkillsError(String message) { super(message); }
    public SkillsError(String message, Throwable cause) { super(message, cause); }
}
