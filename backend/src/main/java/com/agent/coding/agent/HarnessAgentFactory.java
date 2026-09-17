package com.agent.coding.agent;

import com.agent.coding.SettingsService;
import com.agent.coding.service.ModelRoutingService;
import com.agent.coding.skill.SkillStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Single construction point for turn-running {@link HarnessAgent} instances
 * (Factory Method as a Spring component).
 *
 * <p>Console chat, channel dispatch, cron, heartbeat and subagent each used
 * to hand-roll the same builder sequence — profile display name,
 * contract-protected system prompt, routed model, toolkit, workspace — and
 * the copies had drifted: some dropped {@code agentId}, some computed a
 * display name and then ignored it, one fell back to a hardcoded model that
 * can only fail at call time. The factory fixes the common shape once;
 * callers choose the model fallback policy and add hooks on the returned
 * builder.
 */
@Component
public class HarnessAgentFactory {

    /** Behaviour when no routing slot resolves for the agent. */
    public enum ModelFallback {
        /** Use the legacy single-model settings row (apiKey/baseUrl/model). */
        SETTINGS,
        /** No fallback — the caller decides how to report the gap. */
        NONE
    }

    private final ModelRoutingService modelRouting;
    private final SettingsService settingsService;
    private final Toolkit toolkit;

    public HarnessAgentFactory(ModelRoutingService modelRouting,
                               SettingsService settingsService,
                               @Lazy Toolkit toolkit) {
        this.modelRouting = modelRouting;
        this.settingsService = settingsService;
        this.toolkit = toolkit;
    }

    /** Display name from the agent profile, or the agent id when unset. */
    public String agentName(String agentId) {
        var profile = AgentStore.getProfile(agentId);
        if (profile != null && profile.get("name") != null
                && !String.valueOf(profile.get("name")).isBlank()) {
            return String.valueOf(profile.get("name"));
        }
        return agentId;
    }

    /** Agent workspace directory, or the global working dir on failure. */
    public Path workspaceFor(String agentId) {
        try {
            return AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            return SkillStore.WORKING_DIR;
        }
    }

    /**
     * Resolve the effective model: routing slot (agent → global active
     * model) first, then the configured {@link ModelFallback}. Returns null
     * only with {@link ModelFallback#NONE} and no resolvable slot.
     */
    public OpenAIChatModel modelFor(String agentId, ModelFallback fallback) {
        var slot = modelRouting.resolveEffectiveModel(agentId);
        if (slot != null && slot.hasBoth()) {
            return modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        }
        if (fallback == ModelFallback.SETTINGS) {
            return OpenAIChatModel.builder()
                    .apiKey(settingsService.getApiKey())
                    .baseUrl(settingsService.getBaseUrl())
                    .modelName(settingsService.getModelName())
                    .build();
        }
        return null;
    }

    /**
     * Prewired builder: display name + agentId, contract-protected system
     * prompt, model, toolkit and workspace. Chain hooks on the returned
     * builder, then {@code build()}.
     */
    public HarnessAgent.Builder builder(String agentId, String sysPrompt,
                                        Path workspace, OpenAIChatModel model) {
        return HarnessAgent.builder()
                .name(agentName(agentId))
                .agentId(agentId)
                .sysPrompt(ProtectedPrompt.withContract(sysPrompt))
                .model(model)
                .toolkit(toolkit)
                .workspace(workspace);
    }
}
