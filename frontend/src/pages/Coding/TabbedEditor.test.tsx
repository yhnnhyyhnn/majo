import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import TabbedEditor from "./TabbedEditor";

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

vi.mock("../../contexts/ThemeContext", () => ({
  useTheme: () => ({ isDark: false }),
}));

vi.mock("../../stores/agentStore", () => ({
  useAgentStore: () => ({ selectedAgent: "default" }),
}));

vi.mock("../../stores/codingTabsStore", () => ({
  useCurrentDiffs: () => ({}),
  useCodingTabsStore: () => ({
    setDiff: vi.fn(),
    removeDiff: vi.fn(),
    updateDiffModified: vi.fn(),
    updateDiffOriginal: vi.fn(),
  }),
}));

vi.mock("../../api/modules/workspace", () => ({
  workspaceApi: {
    saveCodeFile: vi.fn().mockResolvedValue({}),
    getBinaryFileUrl: vi.fn(),
  },
}));

vi.mock("../../hooks/useWorkspaceWatch", () => ({
  useWorkspaceWatch: vi.fn(),
}));

vi.mock("../../utils/downloadFileFromUrl", () => ({
  downloadFileFromUrl: vi.fn(),
}));

vi.mock("@monaco-editor/react", () => ({
  default: ({ path }: { path: string }) => (
    <div data-testid="monaco-editor">{path}</div>
  ),
  DiffEditor: () => <div data-testid="monaco-diff" />,
}));

vi.mock("./FilePreview", () => ({
  default: ({ filePath }: { filePath: string }) => (
    <div data-testid="file-preview">{filePath}</div>
  ),
  isPreviewable: (p: string) => /\.(md|json|csv)$/.test(p),
}));

vi.mock("antd", async (importOriginal) => {
  const actual = await importOriginal<typeof import("antd")>();
  return {
    ...actual,
    Tooltip: ({ children }: any) => <>{children}</>,
  };
});

const baseProps = {
  tabs: [
    { path: "data.json", content: '{"a":1}', dirty: false },
  ] as any,
  activeTabPath: "data.json",
  onTabSelect: vi.fn(),
  onTabClose: vi.fn(),
  onTabDirtyChange: vi.fn(),
  onTabContentChange: vi.fn(),
};

describe("TabbedEditor preview toggle", () => {
  it("shows preview by default for json", () => {
    render(<TabbedEditor {...baseProps} />);
    expect(screen.getByTestId("file-preview")).toBeTruthy();
    expect(screen.queryByTestId("monaco-editor")).toBeFalsy();
  });

  it("switches to source and back to preview for json", () => {
    const { container } = render(<TabbedEditor {...baseProps} />);
    const toolbarButtons = container.querySelectorAll(
      '[class*="toolbar"] button',
    );
    const codeBtn = Array.from(toolbarButtons).find((b) =>
      b.querySelector('svg, [class*="icon"]'),
    );
    fireEvent.click(codeBtn!);
    expect(screen.getByTestId("monaco-editor")).toBeTruthy();
    expect(screen.queryByTestId("file-preview")).toBeFalsy();
    const previewBtn = container.querySelectorAll('[class*="toolbar"] button');
    fireEvent.click(previewBtn[0]);
    expect(screen.getByTestId("file-preview")).toBeTruthy();
    expect(screen.queryByTestId("monaco-editor")).toBeFalsy();
  });
});

describe("TabbedEditor tab context menu", () => {
  const multiTabProps = {
    tabs: [
      { path: "a.json", content: "{}", dirty: false },
      { path: "b.md", content: "# b", dirty: false },
      { path: "c.txt", content: "c", dirty: false },
    ] as any,
    activeTabPath: "a.json",
    onTabSelect: vi.fn(),
    onTabClose: vi.fn(),
    onTabDirtyChange: vi.fn(),
    onTabContentChange: vi.fn(),
  };

  it("opens context menu with close and close-others on right click", async () => {
    const { findAllByText } = render(<TabbedEditor {...multiTabProps} />);
    const tabA = screen.getByRole("tab", { name: /a\.json/i });
    fireEvent.contextMenu(tabA);
    expect(await findAllByText("files.closeTab")).toBeTruthy();
    expect(await findAllByText("files.closeOtherTabs")).toBeTruthy();
  });

  it("closes the tab from the context menu", async () => {
    const { findAllByText } = render(<TabbedEditor {...multiTabProps} />);
    const tabB = screen.getByRole("tab", { name: /b\.md/i });
    fireEvent.contextMenu(tabB);
    const closeItems = await findAllByText("files.closeTab");
    fireEvent.click(closeItems[closeItems.length - 1]);
    expect(multiTabProps.onTabClose).toHaveBeenCalledWith("b.md");
  });

  it("closes all other tabs from the context menu", async () => {
    const { findAllByText } = render(<TabbedEditor {...multiTabProps} />);
    const tabA = screen.getByRole("tab", { name: /a\.json/i });
    fireEvent.contextMenu(tabA);
    const closeOthers = await findAllByText("files.closeOtherTabs");
    fireEvent.click(closeOthers[closeOthers.length - 1]);
    expect(multiTabProps.onTabClose).toHaveBeenCalledWith("b.md");
    expect(multiTabProps.onTabClose).toHaveBeenCalledWith("c.txt");
  });

  it("closes a tab on middle-click", () => {
    const { container } = render(<TabbedEditor {...multiTabProps} />);
    const tabs = Array.from(container.querySelectorAll('[role="tab"]'));
    const tabC = tabs.find((t) => t.textContent?.includes("c.txt"));
    fireEvent.click(tabC!, { button: 1 });
    expect(multiTabProps.onTabClose).toHaveBeenCalledWith("c.txt");
  });
});
