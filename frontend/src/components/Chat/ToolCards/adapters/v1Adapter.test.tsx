import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import React from "react";

import { adaptCardForV1 } from "./v1Adapter";
import { ToolCallTurnEndedContext } from "../shared/ToolCallTurnContext";
import type { ToolCallContent } from "../shared/types";

/** Props shape @agentscope-ai/chat passes to custom tool renderers. */
function v1Props(overrides: {
  status?: string;
  withResult?: boolean;
}): Record<string, unknown> {
  const content: Array<Record<string, unknown>> = [
    { data: { id: "t1", name: "read_file", arguments: '{"path":"a.md"}' } },
  ];
  if (overrides.withResult) {
    content.push({
      data: { output: "file body", state: overrides.status },
    });
  }
  return {
    data: { content, status: overrides.status ?? "in_progress" },
  };
}

/** Probe card capturing the ToolCallContent the adapter derives. */
function makeProbe(captured: ToolCallContent[]): React.FC<any> {
  return (props: { content: ToolCallContent }) => {
    captured.push(props.content);
    return null;
  };
}

function renderCard(
  Card: React.FC<any>,
  props: Record<string, unknown>,
  turnEnded: boolean,
) {
  return render(
    <ToolCallTurnEndedContext.Provider value={turnEnded}>
      <Card {...props} />
    </ToolCallTurnEndedContext.Provider>,
  );
}

describe("v1Adapter dangling-call closure (#7345 port)", () => {
  it("keeps a pending call 'calling' while its turn streams", () => {
    const captured: ToolCallContent[] = [];
    const Card = adaptCardForV1(makeProbe(captured));
    renderCard(Card, v1Props({ status: "in_progress" }), false);
    expect(captured[captured.length - 1].status).toBe("calling");
    expect(captured[captured.length - 1].interrupted).toBeFalsy();
  });

  it("closes a dangling call as interrupted once the turn ended", () => {
    const captured: ToolCallContent[] = [];
    const Card = adaptCardForV1(makeProbe(captured));
    renderCard(Card, v1Props({ status: "in_progress" }), true);
    const last = captured[captured.length - 1];
    expect(last.status).toBe("error");
    expect(last.interrupted).toBe(true);
  });

  it("canceled delivery status is an interruption, not a tool failure", () => {
    const captured: ToolCallContent[] = [];
    const Card = adaptCardForV1(makeProbe(captured));
    renderCard(Card, v1Props({ status: "canceled", withResult: true }), false);
    const last = captured[captured.length - 1];
    expect(last.status).toBe("error");
    expect(last.interrupted).toBe(true);
  });

  it("completed calls are done and never flagged interrupted", () => {
    const captured: ToolCallContent[] = [];
    const Card = adaptCardForV1(makeProbe(captured));
    renderCard(Card, v1Props({ status: "completed", withResult: true }), true);
    const last = captured[captured.length - 1];
    expect(last.status).toBe("done");
    expect(last.interrupted).toBeFalsy();
  });
});
