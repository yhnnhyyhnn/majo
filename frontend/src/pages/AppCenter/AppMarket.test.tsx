// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { MarketPluginEntry } from "@/api/modules/pluginMarket";
import { AppMarket } from "./AppMarket";

const hoisted = vi.hoisted(() => ({
  fetchMarketPlugins: vi.fn(),
  installPlugin: vi.fn(),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en" },
  }),
}));

vi.mock("@/hooks/useAppMessage", () => ({
  useAppMessage: () => ({
    message: { loading: vi.fn(), success: vi.fn(), error: vi.fn() },
  }),
}));

vi.mock("@/utils/openExternalLink", () => ({
  openExternalLink: vi.fn(),
}));

vi.mock("@/api/modules/pluginMarket", async () => {
  const actual = await vi.importActual<
    typeof import("@/api/modules/pluginMarket")
  >("@/api/modules/pluginMarket");
  return {
    ...actual,
    fetchMarketPlugins: hoisted.fetchMarketPlugins,
  };
});

vi.mock("@/api/modules/plugin", () => ({
  installPlugin: hoisted.installPlugin,
}));

function makeEntry(
  id: string,
  overrides: Partial<MarketPluginEntry> = {},
): MarketPluginEntry {
  return {
    id,
    display_name: id,
    developer: "dev",
    owner: "owner",
    version: "1.0.0",
    logo_url: null,
    downloads: 42,
    view_count: 10,
    details_url: null,
    locales: { en: { description: `${id} description`, category: "app" } },
    ...overrides,
  };
}

describe("AppMarket", () => {
  beforeEach(() => {
    hoisted.fetchMarketPlugins.mockReset();
    hoisted.installPlugin.mockReset();
    hoisted.fetchMarketPlugins.mockResolvedValue({ plugins: [], total: 0 });
  });

  it("shows featured apps only in the official channel", async () => {
    hoisted.fetchMarketPlugins.mockResolvedValue({
      plugins: [
        makeEntry("agent-kanban", { is_featured: true }),
        makeEntry("zalo-channel", { is_featured: false }),
      ],
      total: 2,
    });

    render(<AppMarket channel="official" onInstalled={vi.fn()} />);

    expect(await screen.findByText("agent-kanban")).toBeInTheDocument();
    expect(screen.queryByText("zalo-channel")).not.toBeInTheDocument();
    expect(screen.getByText("appCenter.featured")).toBeInTheDocument();
  });

  it("shows non-featured apps only in the community channel", async () => {
    hoisted.fetchMarketPlugins.mockResolvedValue({
      plugins: [
        makeEntry("agent-kanban", { is_featured: true }),
        makeEntry("zalo-channel", { is_featured: false }),
        makeEntry("mahjong4"),
      ],
      total: 3,
    });

    render(<AppMarket onInstalled={vi.fn()} />);

    expect(await screen.findByText("zalo-channel")).toBeInTheDocument();
    expect(screen.getByText("mahjong4")).toBeInTheDocument();
    expect(screen.queryByText("agent-kanban")).not.toBeInTheDocument();
  });

  it("loads all server pages before applying the channel filter", async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) =>
      makeEntry(`community-${index}`, { is_featured: false }),
    );
    hoisted.fetchMarketPlugins.mockImplementation(({ page_number }) =>
      Promise.resolve(
        page_number === 1
          ? { plugins: firstPage, total: 101 }
          : {
              plugins: [
                makeEntry("official-on-page-two", { is_featured: true }),
              ],
              total: 101,
            },
      ),
    );

    render(<AppMarket channel="official" onInstalled={vi.fn()} />);

    expect(await screen.findByText("official-on-page-two")).toBeInTheDocument();
    expect(hoisted.fetchMarketPlugins).toHaveBeenCalledTimes(2);
    expect(hoisted.fetchMarketPlugins).toHaveBeenLastCalledWith(
      expect.objectContaining({ page_number: 2, page_size: 100 }),
    );
  });

  it("drops the result of an obsolete request when a new search starts", async () => {
    let resolveStale!: (value: unknown) => void;
    hoisted.fetchMarketPlugins
      .mockResolvedValueOnce({ plugins: [], total: 0 })
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveStale = resolve;
          }),
      )
      .mockResolvedValueOnce({
        plugins: [makeEntry("latest-result")],
        total: 1,
      });

    render(<AppMarket onInstalled={vi.fn()} />);
    await waitFor(() =>
      expect(hoisted.fetchMarketPlugins).toHaveBeenCalledTimes(1),
    );

    const search = screen.getByRole("textbox", {
      name: "appCenter.searchMarket",
    });
    fireEvent.change(search, { target: { value: "stale" } });
    fireEvent.keyDown(search, { key: "Enter" });

    fireEvent.change(search, { target: { value: "" } });

    expect(await screen.findByText("latest-result")).toBeInTheDocument();
    resolveStale?.({ plugins: [makeEntry("stale-result")], total: 1 });
    await waitFor(() =>
      expect(screen.queryByText("stale-result")).not.toBeInTheDocument(),
    );
  });

  it("renders community apps in a single grid", async () => {
    hoisted.fetchMarketPlugins.mockResolvedValue({
      plugins: [
        makeEntry("regular-app"),
        makeEntry("another-app", { is_featured: false }),
      ],
      total: 2,
    });

    const { container } = render(<AppMarket onInstalled={vi.fn()} />);

    await screen.findByText("regular-app");
    const grids = container.querySelectorAll("[class*='grid']");
    expect(grids).toHaveLength(1);
  });

  it("installs an app and notifies the parent to refresh", async () => {
    hoisted.fetchMarketPlugins.mockResolvedValue({
      plugins: [makeEntry("installable")],
      total: 1,
    });
    const installResult = { id: "installable", name: "installable" };
    hoisted.installPlugin.mockResolvedValue(installResult);
    const onInstalled = vi.fn();

    render(<AppMarket onInstalled={onInstalled} />);

    fireEvent.click(await screen.findByText("appCenter.install"));

    await waitFor(() =>
      expect(onInstalled).toHaveBeenCalledWith(installResult),
    );
    expect(hoisted.installPlugin).toHaveBeenCalledTimes(1);
  });

  it("disables repeat installs while an install is in flight", async () => {
    hoisted.fetchMarketPlugins.mockResolvedValue({
      plugins: [makeEntry("slow-install")],
      total: 1,
    });
    hoisted.installPlugin.mockReturnValue(new Promise(() => {}));

    render(<AppMarket onInstalled={vi.fn()} />);

    const installBtn = await screen.findByText("appCenter.install");
    fireEvent.click(installBtn);
    await screen.findByText("appCenter.installing");
    fireEvent.click(screen.getByText("appCenter.installing"));

    expect(hoisted.installPlugin).toHaveBeenCalledTimes(1);
  });

  it("shows the market error inside the market view", async () => {
    hoisted.fetchMarketPlugins.mockRejectedValue(new Error("boom"));

    render(<AppMarket onInstalled={vi.fn()} />);

    expect(
      await screen.findByText("pluginManager.marketUnavailable"),
    ).toBeInTheDocument();
  });
});
