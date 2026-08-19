import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import FilePreview, { getPreviewType } from "./FilePreview";

vi.mock("@ant-design/x-markdown", () => ({
  XMarkdown: ({ content }: { content: string }) => <div>md:{content}</div>,
}));

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
}));

vi.mock("../../components/MermaidCodeBlock", () => ({
  mermaidComponents: {},
}));

vi.mock("../../stores/agentStore", () => ({
  useAgentStore: () => ({ selectedAgent: "default" }),
}));

describe("getPreviewType", () => {
  it("detects json and jsonc as previewable", () => {
    expect(getPreviewType("config.json")).toBe("json");
    expect(getPreviewType("tsconfig.json")).toBe("json");
    expect(getPreviewType("settings.jsonc")).toBe("json");
  });

  it("keeps other text files non-previewable", () => {
    expect(getPreviewType("index.ts")).toBe("none");
    expect(getPreviewType("readme.txt")).toBe("none");
  });
});

describe("FilePreview json", () => {
  it("formats valid JSON with syntax highlighting", () => {
    const { container } = render(
      <FilePreview filePath="data.json" content='{"a":1,"b":[2,3]}' />,
    );
    expect(container.textContent).toContain('"a"');
    expect(container.textContent).toContain("1");
  });

  it("shows an error panel for invalid JSON", () => {
    render(<FilePreview filePath="data.json" content="{not json" />);
    expect(screen.getByText("Invalid JSON")).toBeTruthy();
    expect(screen.getByText("{not json")).toBeTruthy();
  });

  it("renders nothing for non-previewable extensions", () => {
    const { container } = render(
      <FilePreview filePath="index.ts" content="const x = 1" />,
    );
    expect(container.textContent).toBe("");
  });
});
