package com.agent.coding.skill;

/** Raised when a workspace/agent id does not resolve. */
public class SkillNotFoundException extends SkillsError {
    public SkillNotFoundException(String message) { super(message); }
}
