import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MermaidToggleBlock } from "./MermaidCodeBlock";

vi.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (key: string) =>
      key === "common.preview" ? "Preview" : key === "common.source" ? "Source" : key,
  }),
}));

vi.mock("mermaid", () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ svg: "<svg>diagram</svg>" }),
  },
}));

const chart = "graph TB\n  A --> B";

describe("MermaidToggleBlock", () => {
  it("renders the diagram preview by default", async () => {
    render(<MermaidToggleBlock chart={chart} />);
    expect(screen.getByRole("tab", { name: "Preview" })).toBeTruthy();
    expect(screen.getByRole("tab", { name: "Source" })).toBeTruthy();
    expect(screen.getByRole("tab", { name: "Preview" }).getAttribute("aria-selected")).toBe("true");
    await screen.findByText(/diagram/);
  });

  it("switches to the raw mermaid source", () => {
    const { container } = render(<MermaidToggleBlock chart={chart} />);
    fireEvent.click(screen.getByRole("tab", { name: "Source" }));
    expect(screen.getByRole("tab", { name: "Source" }).getAttribute("aria-selected")).toBe("true");
    expect(container.textContent).toContain("A");
    expect(container.textContent).toContain("B");
  });

  it("defaults to source view when requested", () => {
    render(<MermaidToggleBlock chart={chart} defaultView="source" />);
    expect(screen.getByRole("tab", { name: "Source" }).getAttribute("aria-selected")).toBe("true");
  });
});
