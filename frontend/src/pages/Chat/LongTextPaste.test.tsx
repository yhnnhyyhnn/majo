/**
 * LongTextPaste component tests (QwenPaw #8119 port): the provider's choice
 * dialog drives what the wrapped input does with a very long paste — text
 * insertion via the DOM, file attach via a replayed paste event, and plain
 * fall-through for short texts, file pastes and disabled inputs.
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// Matches the suite-wide convention (ChatPage.test.tsx): t() returns the
// key, so assertions match key strings.
vi.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: "en" },
  }),
}));

import { LongTextPasteInput, LongTextPasteProvider } from "./LongTextPaste";

// jsdom has no DataTransfer; a minimal fake covering the member surface the
// component and tests touch (text data, files, items.add).
class FakeDataTransfer {
  private data = new Map<string, string>();
  files: File[] = [];
  items = {
    add: (file: File) => {
      (this.files as File[]).push(file);
    },
  };
  setData(type: string, value: string) {
    this.data.set(type, value);
  }
  getData(type: string) {
    return this.data.get(type) ?? "";
  }
}
if (!("DataTransfer" in globalThis)) {
  (globalThis as Record<string, unknown>).DataTransfer = FakeDataTransfer;
}

const LONG = "x".repeat(10_001);
const SHORT = "short text";

function Harness({
  onPaste,
  disabled,
}: {
  onPaste?: React.ClipboardEventHandler<HTMLTextAreaElement>;
  disabled?: boolean;
}) {
  return (
    <LongTextPasteProvider enabled scopeKey="s1">
      <LongTextPasteInput
        onPaste={onPaste}
        disabled={disabled}
        data-testid="ta"
      />
    </LongTextPasteProvider>
  );
}

function paste(ta: HTMLElement, text: string) {
  // jsdom's ClipboardEvent constructor drops the clipboardData init member,
  // so the React handler would always see null; dispatch a plain Event with
  // the payload attached instead (React keys off the event type only).
  const dt = new FakeDataTransfer();
  dt.setData("text/plain", text);
  const ev = new Event("paste", { bubbles: true, cancelable: true });
  Object.defineProperty(ev, "clipboardData", { value: dt });
  ta.dispatchEvent(ev);
}

describe("LongTextPasteInput", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("short pastes fall straight through without a dialog", () => {
    const onPaste = vi.fn();
    render(<Harness onPaste={onPaste} />);
    const ta = screen.getByTestId("ta") as HTMLTextAreaElement;
    paste(ta, SHORT);
    expect(onPaste).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("chat.longTextPaste.characters")).toBeNull();
  });

  it("long paste opens the dialog; cancel leaves the draft untouched", async () => {
    const onPaste = vi.fn();
    render(<Harness onPaste={onPaste} />);
    const ta = screen.getByTestId("ta") as HTMLTextAreaElement;
    paste(ta, LONG);
    expect(onPaste).not.toHaveBeenCalled();
    expect(await screen.findByText("chat.longTextPaste.characters")).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "common.cancel" }));
    // antd's close motion never completes under jsdom, so assert behaviour
    // rather than DOM disappearance: the draft stays empty and the original
    // handler was never invoked with the pasted payload.
    expect(ta.value).toBe("");
    expect(onPaste).not.toHaveBeenCalled();
  });

  it("choosing 'as text' inserts the content into the textarea", async () => {
    const onPaste = vi.fn();
    render(<Harness onPaste={onPaste} />);
    const ta = screen.getByTestId("ta") as HTMLTextAreaElement;
    paste(ta, LONG);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "chat.longTextPaste.asText" }));
    await waitFor(() => expect(ta.value).toBe(LONG));
    expect(onPaste).not.toHaveBeenCalled();
  });

  it("choosing 'as file' replays a file paste through the original handler", async () => {
    const onPaste = vi.fn();
    render(<Harness onPaste={onPaste} />);
    const ta = screen.getByTestId("ta") as HTMLTextAreaElement;
    paste(ta, LONG);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "chat.longTextPaste.asFile" }));
    await waitFor(() => expect(onPaste).toHaveBeenCalledTimes(1));
    const event = onPaste.mock.calls[0][0] as unknown as ClipboardEvent;
    const [file] = Array.from(event.clipboardData?.files ?? []);
    expect(file).toBeTruthy();
    expect(file?.name).toMatch(/^prompt-\d+\.txt$/);
    expect(ta.value).toBe("");
  });

  it("disabled input never opens the dialog", () => {
    const onPaste = vi.fn();
    render(<Harness onPaste={onPaste} disabled />);
    const ta = screen.getByTestId("ta") as HTMLTextAreaElement;
    paste(ta, LONG);
    expect(onPaste).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("chat.longTextPaste.characters")).toBeNull();
  });
});
