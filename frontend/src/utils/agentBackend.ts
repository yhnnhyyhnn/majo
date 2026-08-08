import type { AgentBackend } from "../api/types/agents";
import type { HarnessCapabilities } from "../api/modules/harness";

export function requiresQwenPawModel(backend: AgentBackend): boolean {
  // qwenpaw: the native backend uses the qwenpaw model selector
  // (backend === "qwenpaw"). majo runs every agent through the same model
  // routing, so both the "majo" backend (default agent) and "qwenpaw"
  // (agents created with the qwenpaw backend value) use the native selector.
  return backend === "qwenpaw" || backend === "majo";
}

export function supportsAgentAttachments(
  backend: AgentBackend,
  capabilities?: Partial<HarnessCapabilities>,
): boolean {
  return requiresQwenPawModel(backend) || Boolean(capabilities?.attachments);
}

