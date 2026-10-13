import { useMemo } from "react";
import type { TokenUsageRecord } from "../../../../api/types/tokenUsage";

interface AggregatedData {
  total_prompt_tokens: number;
  total_completion_tokens: number;
  total_calls: number;
  /** Accumulated cost; null when no record in scope was priced (ADR-0017). */
  total_cost_usd: number | null;
  by_model: Record<
    string,
    {
      model: string;
      provider_id: string;
      prompt_tokens: number;
      completion_tokens: number;
      call_count: number;
      cost_usd: number | null;
    }
  >;
  by_date: Record<
    string,
    {
      prompt_tokens: number;
      completion_tokens: number;
      call_count: number;
      cost_usd: number | null;
    }
  >;
  by_date_model: Record<
    string,
    Record<
      string,
      {
        model: string;
        provider_id: string;
        prompt_tokens: number;
        completion_tokens: number;
        call_count: number;
        cost_usd: number | null;
      }
    >
  >;
}

/** Null-safe accumulate: unknown stays null until a priced record lands. */
function addCost(bucket: { cost_usd: number | null }, cost?: number | null) {
  if (cost != null) {
    bucket.cost_usd = (bucket.cost_usd ?? 0) + cost;
  }
}

export function useDataAggregation(records: TokenUsageRecord[]) {
  return useMemo<AggregatedData | null>(() => {
    if (records.length === 0) return null;

    const byModel: AggregatedData["by_model"] = {};
    const byDate: AggregatedData["by_date"] = {};
    const byDateModel: AggregatedData["by_date_model"] = {};

    let totalPrompt = 0;
    let totalCompletion = 0;
    let totalCalls = 0;
    const totalCost = { cost_usd: null as number | null };

    records.forEach((r) => {
      const pt = r.prompt_tokens;
      const ct = r.completion_tokens;
      const calls = r.call_count;
      const providerId = r.provider_id;
      totalPrompt += pt;
      totalCompletion += ct;
      totalCalls += calls;
      addCost(totalCost, r.cost_usd);

      const modelKey = `${providerId}:${r.model}`;
      if (!byModel[modelKey]) {
        byModel[modelKey] = {
          model: r.model,
          provider_id: providerId,
          prompt_tokens: 0,
          completion_tokens: 0,
          call_count: 0,
          cost_usd: null,
        };
      }
      byModel[modelKey].prompt_tokens += pt;
      byModel[modelKey].completion_tokens += ct;
      byModel[modelKey].call_count += calls;
      addCost(byModel[modelKey], r.cost_usd);

      if (!byDate[r.date]) {
        byDate[r.date] = {
          prompt_tokens: 0,
          completion_tokens: 0,
          call_count: 0,
          cost_usd: null,
        };
      }
      byDate[r.date].prompt_tokens += pt;
      byDate[r.date].completion_tokens += ct;
      byDate[r.date].call_count += calls;
      addCost(byDate[r.date], r.cost_usd);

      if (!byDateModel[r.date]) {
        byDateModel[r.date] = {};
      }
      if (!byDateModel[r.date][modelKey]) {
        byDateModel[r.date][modelKey] = {
          model: r.model,
          provider_id: providerId,
          prompt_tokens: 0,
          completion_tokens: 0,
          call_count: 0,
          cost_usd: null,
        };
      }
      byDateModel[r.date][modelKey].prompt_tokens += pt;
      byDateModel[r.date][modelKey].completion_tokens += ct;
      byDateModel[r.date][modelKey].call_count += calls;
      addCost(byDateModel[r.date][modelKey], r.cost_usd);
    });

    return {
      total_prompt_tokens: totalPrompt,
      total_completion_tokens: totalCompletion,
      total_calls: totalCalls,
      total_cost_usd: totalCost.cost_usd,
      by_model: byModel,
      by_date: byDate,
      by_date_model: byDateModel,
    };
  }, [records]);
}
