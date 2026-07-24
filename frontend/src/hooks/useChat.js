import { useState, useRef, useCallback } from "react";

const API = "/api";

export default function useChat(config) {
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const abortRef = useRef(null);
  const sessionRef = useRef("session-" + Date.now());

  const addMsg = useCallback((msg) => {
    setMessages((prev) => [
      ...prev,
      { ...msg, id: msg.id ?? Date.now() + Math.random() },
    ]);
  }, []);

  const finishStream = useCallback((agentMsgId) => {
    abortRef.current = null;
    setMessages((prev) =>
      prev.map((m) =>
        m.id === agentMsgId ? { ...m, streaming: false } : m
      )
    );
    setLoading(false);
  }, []);

  const stop = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort();
    }
  }, []);

  const send = useCallback(
    async (prompt) => {
      if (!prompt.trim() || loading) return;

      const controller = new AbortController();
      abortRef.current = controller;
      setLoading(true);

      addMsg({ role: "user", content: prompt, time: new Date().toLocaleTimeString() });

      const agentMsgId = Date.now();
      addMsg({
        role: "agent",
        content: "",
        thinkingBlocks: [],
        toolCalls: [],
        time: "",
        id: agentMsgId,
        streaming: true,
      });

      try {
        const res = await fetch(API + "/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            prompt,
            sessionId: sessionRef.current,
            workspace: config.workspace,
          }),
          signal: controller.signal,
        });

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        let agentContent = "";
        let thinkingBlocks = [];
        let currentThinking = null;
        let toolCalls = [];
        let currentTool = null;

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split("\n");
          buffer = lines.pop() || "";

          for (const line of lines) {
            if (!line.startsWith("data:")) continue;
            try {
              const data = JSON.parse(line.slice(5).trim());

              if (data.type === "error") {
                const errMsg = data.content || "Unknown error";
                agentContent = agentContent
                  ? agentContent + "\n\n❌ " + errMsg
                  : "❌ " + errMsg;
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === agentMsgId
                      ? {
                          ...m,
                          content: agentContent,
                          thinkingBlocks: thinkingBlocks.map((b) => ({ ...b })),
                          toolCalls: toolCalls.map((t) => ({ ...t })),
                          streaming: false,
                        }
                      : m
                  )
                );
                finishStream(agentMsgId);
                return;
              }

              if (data.type === "done") {
                // handled by stream end
              } else if (data.type === "thinking") {
                // no-op
              } else if (data.type === "ToolCallStartEvent") {
                const d = data.data || {};
                currentTool = {
                  name: d.toolCallName || "tool",
                  id: d.toolCallId || Date.now(),
                  args: "",
                };
                toolCalls.push(currentTool);
              } else if (data.type === "ToolCallDeltaEvent") {
                const d = data.data || {};
                if (currentTool && d.delta) currentTool.args += d.delta;
              } else if (data.type === "ToolCallEndEvent") {
                currentTool = null;
              } else if (data.type === "ToolResultStartEvent") {
                const last = toolCalls[toolCalls.length - 1];
                if (last) last.result = "";
              } else if (data.type === "ToolResultTextDeltaEvent") {
                const d = data.data || {};
                const last = toolCalls[toolCalls.length - 1];
                if (last && d.delta)
                  last.result = (last.result || "") + d.delta;
              } else if (data.type === "ToolResultEndEvent") {
                // end
              } else if (data.type === "ThinkingBlockStartEvent") {
                currentThinking = { id: Date.now(), text: "" };
                thinkingBlocks.push(currentThinking);
              } else if (data.type === "ThinkingBlockDeltaEvent") {
                const d = data.data || {};
                if (currentThinking && d.delta)
                  currentThinking.text += d.delta;
              } else if (data.type === "ThinkingBlockEndEvent") {
                currentThinking = null;
              } else {
                const d = data.data || {};
                if (d.delta) {
                  agentContent += d.delta;
                } else if (d.content) {
                  agentContent += d.content;
                } else if (d.textContent) {
                  agentContent += d.textContent;
                } else if (d.text) {
                  agentContent += d.text;
                } else if (d.toolCallName) {
                  agentContent += "\n[Tool: " + d.toolCallName + "] ";
                }
              }

              setMessages((prev) =>
                prev.map((m) =>
                  m.id === agentMsgId
                    ? {
                        ...m,
                        content: agentContent,
                        thinkingBlocks: thinkingBlocks.map((b) => ({ ...b })),
                        toolCalls: toolCalls.map((t) => ({ ...t })),
                      }
                    : m
                )
              );
            } catch {
              // skip malformed lines
            }
          }
        }
      } catch (e) {
        if (e.name !== "AbortError") {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === agentMsgId
                ? {
                    ...m,
                    content: "Error: " + e.message,
                    streaming: false,
                  }
                : m
            )
          );
        }
      } finally {
        finishStream(agentMsgId);
      }
    },
    [loading, config.workspace, addMsg, finishStream]
  );

  return { messages, loading, send, stop, addMsg };
}
