import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import FilesNavigator, { type FilesSource } from "./FilesNavigator";
import type { DailyMemoryFile, MarkdownFile } from "../../api/types";

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

vi.mock("../../api/modules/projectDirectory", () => ({
  projectDirectoryApi: {
    get: vi.fn().mockResolvedValue({
      path: "/srv/majo/workspaces/default",
      name: "default",
      is_workspace_default: true,
      workspace_dir: "/srv/majo/workspaces/default",
    }),
  },
}));

vi.mock("../../hooks/useAppMessage", () => ({
  useAppMessage: () => ({ message: { success: vi.fn(), error: vi.fn() } }),
}));

vi.mock("../Coding/FileTree", () => ({
  default: ({ onFileSelect }: { onFileSelect: (p: string, c: string) => void }) => (
    <button
      type="button"
      onClick={() => onFileSelect("src/index.ts", "code")}
      data-testid="file-tree"
    >
      file-tree
    </button>
  ),
}));

vi.mock("../../api/modules/workspace", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/modules/workspace")>();
  return {
    ...actual,
    workspaceApi: {
      ...actual.workspaceApi,
      uploadFiles: vi.fn().mockResolvedValue({ files: [] }),
    },
    UploadConflictError: actual.UploadConflictError,
  };
});

vi.mock("antd", async (importOriginal) => {
  const actual = await importOriginal<typeof import("antd")>();
  return {
    ...actual,
    Switch: ({ checked, onClick, "aria-label": ariaLabel }: any) => (
      <button
        type="button"
        aria-label={ariaLabel}
        data-checked={String(checked)}
        onClick={(e: React.MouseEvent) => onClick?.(!checked, e)}
      >
        switch
      </button>
    ),
    Modal: ({ open, children }: any) =>
      open ? <div data-testid="conflict-modal">{children}</div> : null,
    Tooltip: ({ children }: any) => <>{children}</>,
  };
});

const files: MarkdownFile[] = [
  {
    filename: "AGENTS.md",
    path: "AGENTS.md",
    size: 10,
    modified_time: "2026-01-01T00:00:00",
    created_time: "2026-01-01T00:00:00",
    updated_at: 1,
  },
];

const dailyMemories: DailyMemoryFile[] = [
  {
    filename: "2026-01-01.md",
    path: "2026-01-01.md",
    size: 5,
    created_time: "2026-01-01T00:00:00",
    modified_time: "2026-01-01T00:00:00",
    updated_at: 1,
    date: "2026-01-01",
  },
  {
    filename: "digest/wiki/note.md",
    path: "digest/wiki/note.md",
    size: 3,
    created_time: "2026-01-01T00:00:00",
    modified_time: "2026-01-01T00:00:00",
    updated_at: 2,
    date: "note",
  },
];

const props = {
  files,
  activeTabPath: "",
  dailyMemories,
  enabledFiles: ["AGENTS.md"],
  onFileClick: vi.fn(),
  onDailyMemoryClick: vi.fn(),
  onToggleFileEnabled: vi.fn(),
  onWorkspaceFileSelect: vi.fn(),
  onRefresh: vi.fn(),
  fileTreeKey: 0,
};

function renderWithSource(source: FilesSource, onSourceChange = vi.fn()) {
  return render(
    <FilesNavigator
      source={source}
      onSourceChange={onSourceChange}
      {...props}
    />,
  );
}

describe("FilesNavigator", () => {
  it("renders the four source tabs", () => {
    renderWithSource("workspace");
    expect(screen.getByRole("tab", { name: "files.workspace" })).toBeTruthy();
    expect(screen.getByRole("tab", { name: "files.profile" })).toBeTruthy();
    expect(screen.getByRole("tab", { name: "files.daily" })).toBeTruthy();
    expect(screen.getByRole("tab", { name: "files.digest" })).toBeTruthy();
  });

  it("shows the workspace file tree by default and forwards selections", () => {
    renderWithSource("workspace");
    const tree = screen.getByTestId("file-tree");
    fireEvent.click(tree);
    expect(props.onWorkspaceFileSelect).toHaveBeenCalledWith(
      "src/index.ts",
      "code",
    );
  });

  it("lists core profile files on the profile tab", () => {
    renderWithSource("profile");
    expect(screen.getByText("AGENTS.md")).toBeTruthy();
    fireEvent.click(screen.getByText("AGENTS.md"));
    expect(props.onFileClick).toHaveBeenCalledWith(files[0]);
  });

  it("lists daily memories on the daily tab", () => {
    renderWithSource("daily");
    expect(screen.getByText("2026-01-01")).toBeTruthy();
    fireEvent.click(screen.getByText("2026-01-01.md"));
    expect(props.onDailyMemoryClick).toHaveBeenCalledWith(dailyMemories[0]);
  });

  it("lists digest entries on the digest tab", () => {
    renderWithSource("digest");
    expect(screen.getByText("wiki")).toBeTruthy();
    fireEvent.click(screen.getByText("wiki"));
    fireEvent.click(screen.getByText("note.md"));
    expect(props.onDailyMemoryClick).toHaveBeenCalledWith(dailyMemories[1]);
  });

  it("emits source changes when tabs are clicked", () => {
    const onSourceChange = vi.fn();
    renderWithSource("workspace", onSourceChange);
    fireEvent.click(screen.getByRole("tab", { name: "files.profile" }));
    expect(onSourceChange).toHaveBeenCalledWith("profile");
  });

  it("shows the server-side workspace directory path", async () => {
    renderWithSource("workspace");
    await waitFor(() =>
      expect(screen.getByText("/srv/majo/workspaces/default")).toBeTruthy(),
    );
  });

  it("keeps the directory path visible on every source tab", async () => {
    renderWithSource("workspace");
    await waitFor(() =>
      expect(screen.getByText("/srv/majo/workspaces/default")).toBeTruthy(),
    );
    fireEvent.click(screen.getByRole("tab", { name: "files.profile" }));
    expect(screen.getByText("/srv/majo/workspaces/default")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "files.daily" }));
    expect(screen.getByText("/srv/majo/workspaces/default")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "files.digest" }));
    expect(screen.getByText("/srv/majo/workspaces/default")).toBeTruthy();
  });

  it("fires refresh from the always-visible toolbar", async () => {
    renderWithSource("workspace");
    await waitFor(() =>
      expect(screen.getByText("/srv/majo/workspaces/default")).toBeTruthy(),
    );
    fireEvent.click(screen.getByRole("button", { name: "common.refresh" }));
    expect(props.onRefresh).toHaveBeenCalled();
  });

  it("toggles profile file enablement via switch", () => {
    renderWithSource("profile");
    fireEvent.click(screen.getByRole("button", { name: "workspace.systemPromptToggleTooltip" }));
    expect(props.onToggleFileEnabled).toHaveBeenCalledWith("AGENTS.md");
  });
});
