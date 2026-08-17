package com.agent.coding.dto;

/** Loop mode descriptor,'s LoopModeInfo pydantic model. */
public record LoopModeInfo(
    String id,
    String name,
    String slashCommand,
    String description,
    String source
) {
    public static final LoopModeInfo DEFAULT = new LoopModeInfo(
        "default", "default", "",
        "The standard guarded agent loop.", "builtin"
    );

    public static final LoopModeInfo GOAL = new LoopModeInfo(
        "goal", "goal", "goal",
        "Set a goal and work until it is done.", "builtin"
    );

    public static final LoopModeInfo MISSION = new LoopModeInfo(
        "mission", "mission", "mission",
        "Run a persistent multi-step mission.", "builtin"
    );
}
