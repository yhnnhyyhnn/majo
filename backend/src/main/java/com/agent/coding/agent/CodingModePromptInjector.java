package com.agent.coding.agent;

import io.agentscope.core.hook.PreCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Injects the coding discipline system prompt when Coding Mode is enabled
 * (ADR-0010), ported from QwenPaw's CodingModeContributor
 * ({@code _CODING_SYSTEM_PROMPT_TEMPLATE}). Runs on PRE_CALL via
 * {@code ToolGuardHook} — same hot-injection point as the AGENT.md loader
 * (ADR-0009): re-resolved every call, so config and workspace changes take
 * effect on the next message without an agent rebuild.
 */
@Component
public class CodingModePromptInjector {

    private static final Logger log = LoggerFactory.getLogger(CodingModePromptInjector.class);
    private static final String MARKER = "<coding-mode-discipline>";

    private final CodingModeService codingModeService;

    public CodingModePromptInjector(CodingModeService codingModeService) {
        this.codingModeService = codingModeService;
    }

    /** PRE_CALL branch: append the coding prompt when enabled. */
    @SuppressWarnings("unused")
    public void onPreCall(PreCallEvent event) {
        try {
            String agentId = agentIdOf(event);
            if (!codingModeService.isEnabled(agentId)) {
                return;
            }
            Path workspace;
            try {
                workspace = AgentStore.workspaceDirForAgent(agentId);
            } catch (Exception e) {
                return;
            }
            String existing = event.getSystemMessage() == null
                    ? "" : event.getSystemMessage().getTextContent();
            if (existing.contains(MARKER)) {
                return; // already injected (nested agent calls share the hook)
            }
            event.appendSystemContent("\n\n" + MARKER + "\n" + buildPrompt(workspace));
        } catch (Exception e) {
            log.debug("[coding-mode] prompt injection skipped: {}", e.getMessage());
        }
    }

    private static String buildPrompt(Path workspace) {
        return """
                # Coding Mode

                You are operating in Coding Mode on the project at `%s`. Sibling
                directories are unrelated repositories — never go hunting for another
                project.

                ## Task tracking
                - For any non-trivial task, pick an UPPERCASE_SNAKE_CASE slug (<= 24
                  chars, fallback CODING) and create/overwrite `%s` at the
                  project root with a `- [ ]` checklist before starting work.
                - Flip `- [ ]` to `- [x]` immediately when a step is fully done —
                  never batch-update checkboxes at the end.
                - When creating a new *_TODO.md, append `*_TODO.md` to `.gitignore`
                  (session-local notes are not committed).

                ## Code references
                - Reference code as `relative/path/File.java:42` (ranges `:42-58`),
                  always project-relative.

                ## Tool preferences
                - "Where is X defined / who calls Y / what symbols does Z have" →
                  use the `lsp` tool first; it will tell you which languages have a
                  language server. No server for that language → fall back to
                  `search_code`.
                - Structural pattern queries (e.g. "all functions taking Request and
                  returning Response") → `ast_search` first. It is strictly
                  read-only: to rewrite, read each match then apply targeted
                  `edit_file` calls.
                - grep (`search_code`) is the last resort for plain text search.

                ## Working discipline
                - Resolve relative paths against the project directory.
                - Read before you write; prefer targeted `edit_file` over whole-file
                  rewrites; change only what the task requires.
                - Give a short summary after each batch of changes."""
                .formatted(workspace.toString().replace('\\', '/'), "{SLUG}_TODO.md");
    }

    private static String agentIdOf(PreCallEvent event) {
        try {
            if (event.getAgent() != null && event.getAgent().getName() != null) {
                return event.getAgent().getName();
            }
        } catch (Exception ignored) {
        }
        return "default";
    }
}
