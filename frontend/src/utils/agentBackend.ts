import type { AgentBackend } from "../api/types/agents";
import type { HarnessCapabilities } from "../api/modules/harness";

export function requiresMajoModel(backend: AgentBackend): boolean {
  // The native backend uses the Majo model selector; all other backends
  // (codex, qoder) use their own runtime's model management.
  return backend === "majo";
}

export function supportsAgentAttachments(
  backend: AgentBackend,
  capabilities?: Partial<HarnessCapabilities>,
): boolean {
  return requiresMajoModel(backend) || Boolean(capabilities?.attachments);
}

