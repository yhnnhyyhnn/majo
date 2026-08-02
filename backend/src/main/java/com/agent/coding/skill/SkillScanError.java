package com.agent.coding.skill;

/** Raised when a skill scan finds CRITICAL/HIGH findings that block a write. */
public class SkillScanError extends SkillsError {
    public final String skillName;
    public final java.util.List<java.util.Map<String, Object>> findings;

    public SkillScanError(String message, String skillName,
                          java.util.List<java.util.Map<String, Object>> findings) {
        super(message, null);
        this.skillName = skillName;
        this.findings = findings == null ? new java.util.ArrayList<>() : findings;
    }
}
