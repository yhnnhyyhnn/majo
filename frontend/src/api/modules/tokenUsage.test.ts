import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

vi.mock("../request", () => ({
  request: vi.fn(),
}));

import { tokenUsageApi } from "./tokenUsage";
import { request } from "../request";

describe("tokenUsageApi", () => {
  beforeEach(() => {
    vi.mocked(request).mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("getTokenUsage builds query with start_date + end_date only", async () => {
    const summary = { total_tokens: 100 } as any;
    vi.mocked(request).mockResolvedValue(summary);
    const result = await tokenUsageApi.getTokenUsage({
      start_date: "2026-01-01",
      end_date: "2026-01-31",
    });
    expect(request).toHaveBeenCalledWith(
      "/token-usage?start_date=2026-01-01&end_date=2026-01-31",
    );
    expect(result).toEqual(summary);
  });

  it("getTokenUsageDetails includes model + provider when provided", async () => {
    const records = [{ id: "r1" }] as any;
    vi.mocked(request).mockResolvedValue(records);
    const result = await tokenUsageApi.getTokenUsageDetails({
      start_date: "2026-01-01",
      end_date: "2026-01-31",
      model: "gpt-4",
      provider: "openai",
    });
    expect(request).toHaveBeenCalledWith(
      "/token-usage/details?start_date=2026-01-01&end_date=2026-01-31&model=gpt-4&provider=openai",
    );
    expect(result).toEqual(records);
  });

  it("getTokenUsageDetails omits model/provider query when not provided", async () => {
    const records = [] as any;
    vi.mocked(request).mockResolvedValue(records);
    await tokenUsageApi.getTokenUsageDetails({
      start_date: "2026-02-01",
      end_date: "2026-02-28",
    });
    expect(request).toHaveBeenCalledWith(
      "/token-usage/details?start_date=2026-02-01&end_date=2026-02-28",
    );
  });

  it("getTokenUsageByAgent passes start_date when provided", async () => {
    const res = { agents: [{ agent_id: "default", input_tokens: 1 }] } as any;
    vi.mocked(request).mockResolvedValue(res);
    const result = await tokenUsageApi.getTokenUsageByAgent("2026-03-01");
    expect(request).toHaveBeenCalledWith("/token-usage/agents?start_date=2026-03-01");
    expect(result).toEqual(res);
  });

  it("getTokenUsageByAgent omits the query when no start date", async () => {
    vi.mocked(request).mockResolvedValue({ agents: [] } as any);
    await tokenUsageApi.getTokenUsageByAgent();
    expect(request).toHaveBeenCalledWith("/token-usage/agents");
  });
});
