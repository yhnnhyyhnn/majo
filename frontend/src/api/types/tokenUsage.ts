/** Single token usage record (per date + provider + model). */
export interface TokenUsageRecord {
  date: string; // YYYY-MM-DD
  provider_id: string;
  model: string;
  prompt_tokens: number;
  completion_tokens: number;
  call_count: number;
  /** Cost snapshot; null = price unknown at record time (ADR-0017). */
  cost_usd?: number | null;
}

/** Per-model (has provider_id, model) or per-date (no provider_id, model) stats. */
export interface TokenUsageStats {
  provider_id?: string;
  model?: string;
  prompt_tokens: number;
  completion_tokens: number;
  call_count: number;
  /** Accumulated cost; null when nothing in scope was priced. */
  cost_usd?: number | null;
}

export interface TokenUsageSummary {
  total_prompt_tokens: number;
  total_completion_tokens: number;
  total_calls: number;
  total_cost_usd?: number | null;
  by_model: Record<string, TokenUsageStats>;
  by_date: Record<string, TokenUsageStats>;
}

/** Per-agent aggregate token usage (GET /token-usage/agents, ADR-0011). */
export interface TokenUsageAgentStat {
  agent_id: string;
  input_tokens: number;
  output_tokens: number;
  turns: number;
  duration_ms: number;
  cost_usd?: number;
}
