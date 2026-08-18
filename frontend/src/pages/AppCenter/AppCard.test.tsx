// @vitest-environment jsdom
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AppCard, pickAppDescription, type AppCardData } from "./AppCard";

vi.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en" },
  }),
}));

function makeApp(overrides: Partial<AppCardData> = {}): AppCardData {
  return {
    id: "test-app",
    name: "Test App",
    version: "1.0.0",
    description: "English description",
    category: "tools",
    icon: "📋",
    entry_page: "/apps/test-app",
    status: "installed",
    ...overrides,
  };
}

describe("AppCard", () => {
  beforeEach(() => {
    vi.spyOn(HTMLImageElement.prototype, "src", "get").mockReturnValue("");
  });

  it("renders the app name and description", () => {
    render(<AppCard app={makeApp()} onClick={vi.fn()} />);

    expect(screen.getByText("Test App")).toBeInTheDocument();
    expect(screen.getByText("English description")).toBeInTheDocument();
  });

  it("calls onClick when the card open area is activated", () => {
    const onClick = vi.fn();
    render(<AppCard app={makeApp()} onClick={onClick} />);

    fireEvent.click(screen.getByText("Test App"));
    expect(onClick).toHaveBeenCalledWith(expect.objectContaining({ id: "test-app" }));
  });

  it("supports keyboard activation via Enter", () => {
    const onClick = vi.fn();
    render(<AppCard app={makeApp()} onClick={onClick} />);

    const openArea = screen.getByRole("button", { name: "Test App" });
    fireEvent.keyDown(openArea, { key: "Enter" });
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("renders the uninstall action when onUninstall is provided", async () => {
    const onUninstall = vi.fn();
    render(<AppCard app={makeApp()} onClick={vi.fn()} onUninstall={onUninstall} />);

    const moreBtn = screen.getByRole("button", { name: "appCenter.moreActions" });
    fireEvent.click(moreBtn);
    fireEvent.click(await screen.findByText("appCenter.uninstall"));

    expect(onUninstall).toHaveBeenCalledWith(expect.objectContaining({ id: "test-app" }));
  });

  it("shows an image icon when icon_url is a URL", () => {
    render(
      <AppCard
        app={makeApp({ icon_url: "https://example.com/icon.png" })}
        onClick={vi.fn()}
      />,
    );

    const img = document.querySelector("img");
    expect(img).not.toBeNull();
    expect(img?.getAttribute("src")).toBe("https://example.com/icon.png");
  });

  it("falls back to the emoji icon when icon_url is missing", () => {
    render(<AppCard app={makeApp()} onClick={vi.fn()} />);

    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByText("📋")).toBeInTheDocument();
  });
});

describe("pickAppDescription", () => {
  it("returns the plain description when no i18n map exists", () => {
    const app = makeApp({ description_i18n: undefined });
    expect(pickAppDescription(app, "zh-CN")).toBe("English description");
  });

  it("prefers the exact locale key", () => {
    const app = makeApp({
      description_i18n: { "zh-CN": "中文描述", en: "English" },
    });
    expect(pickAppDescription(app, "zh-CN")).toBe("中文描述");
  });

  it("matches language prefix when the exact locale is absent", () => {
    const app = makeApp({ description_i18n: { zh: "中文描述" } });
    expect(pickAppDescription(app, "zh-CN")).toBe("中文描述");
  });

  it("falls back to an English variant", () => {
    const app = makeApp({
      description_i18n: { "en-US": "English variant" },
    });
    expect(pickAppDescription(app, "zh-CN")).toBe("English variant");
  });
});
