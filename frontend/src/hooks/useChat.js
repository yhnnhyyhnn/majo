import { useState, useRef, useCallback } from "react";

const API = "/api";

export default function useChat(workspace, modelId) {
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [convId, setConvId] = useState(null);
  const abortRef = useRef(null);
  const sessionRef = useRef("session-" + Date.now());
  const modelIdRef = useRef(modelId);
  modelIdRef.current = modelId; // keep in sync

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
    if (abortRef.current) abortRef.current.abort();
  }, []);

  const loadConversation = useCallback(async (id) => {
    setConvId(id);
    setMessages([]);
    try {
      const res = await fetch(API + "/conversations/" + id + "/messages");
      const data = await res.json();
      const msgs = data.map((m) => {
        let meta = {};
        try { meta = m.metadata ? JSON.parse(m.metadata) : {}; } catch {}
        return {
          id: m.id,
          role: m.role,
          content: m.content,
          time: m.time ? new Date(m.time).toLocaleTimeString() : "",
          streaming: false,
          thinkingBlocks: meta.thinkingBlocks || [],
          toolCalls: meta.toolCalls || [],
        };
      });
      setMessages(msgs);
    } catch {}
  }, []);

  const createConversation = useCallback(async () => {
    try {
      const res = await fetch(API + "/conversations", { method: "POST" });
      const data = await res.json();
      setConvId(data.id);
      return data.id;
    } catch {
      return null;
    }
  }, []);

  const saveExchange = useCallback(async (cid, userMsg, agentMsg) => {
    const payload = [
      { role: userMsg.role, content: userMsg.content, metadata: "{}" },
      {
        role: agentMsg.role,
        content: agentMsg.content,
        metadata: JSON.stringify({
          thinkingBlocks: agentMsg.thinkingBlocks || [],
          toolCalls: agentMsg.toolCalls || [],
        }),
      },
    ];
    try {
      await fetch(API + "/conversations/" + cid + "/messages", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
    } catch {}
  }, []);

  const newConversation = useCallback(() => {
    setConvId(null);
    setMessages([]);
  }, []);

  const send = useCallback(
    async (prompt) => {
      if (!prompt.trim() || loading) return;

      const controller = new AbortController();
      abortRef.current = controller;
      setLoading(true);

      const now = Date.now();
      const userMsgId = now;
      const agentMsgId = now + 1;

      const userMsg = {
        role: "user",
        content: prompt,
        time: new Date().toLocaleTimeString(),
        id: userMsgId,
      };
      addMsg(userMsg);
      addMsg({
        role: "agent",
        content: "",
        thinkingBlocks: [],
        toolCalls: [],
        time: "",
        id: agentMsgId,
        streaming: true,
      });

      let cid = convId;
      if (!cid) {
        cid = await createConversation();
        if (!cid) {
          finishStream(agentMsgId);
          return;
        }
      }

      try {
        const res = await fetch(API + "/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            prompt,
            sessionId: sessionRef.current,
            workspace,
            modelId: modelIdRef.current ? String(modelIdRef.current) : "",
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
                      ? { ...m, content: agentContent, streaming: false, thinkingBlocks: thinkingBlocks.map((b) => ({ ...b })), toolCalls: toolCalls.map((t) => ({ ...t })) }
                      : m
                  )
                );
                finishStream(agentMsgId);
                saveExchange(cid, userMsg, { role: "agent", content: agentContent, thinkingBlocks, toolCalls });
                return;
              }

              if (data.type === "done") {
              } else if (data.type === "thinking") {
              } else if (data.type === "ToolCallStartEvent") {
                const d = data.data || {};
                currentTool = { name: d.toolCallName || "tool", id: d.toolCallId || Date.now(), args: "" };
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
                if (last && d.delta) last.result = (last.result || "") + d.delta;
              } else if (data.type === "ToolResultEndEvent") {
              } else if (data.type === "ThinkingBlockStartEvent") {
                currentThinking = { id: Date.now(), text: "" };
                thinkingBlocks.push(currentThinking);
              } else if (data.type === "ThinkingBlockDeltaEvent") {
                const d = data.data || {};
                if (currentThinking && d.delta) currentThinking.text += d.delta;
              } else if (data.type === "ThinkingBlockEndEvent") {
                currentThinking = null;
              } else {
                const d = data.data || {};
                if (d.delta) agentContent += d.delta;
                else if (d.content) agentContent += d.content;
                else if (d.textContent) agentContent += d.textContent;
                else if (d.text) agentContent += d.text;
                else if (d.toolCallName) agentContent += "\n[Tool: " + d.toolCallName + "] ";
              }

              setMessages((prev) =>
                prev.map((m) =>
                  m.id === agentMsgId
                    ? { ...m, content: agentContent, thinkingBlocks: thinkingBlocks.map((b) => ({ ...b })), toolCalls: toolCalls.map((t) => ({ ...t })) }
                    : m
                )
              );
            } catch {}
          }
        }

        saveExchange(cid, userMsg, { role: "agent", content: agentContent, thinkingBlocks, toolCalls });
      } catch (e) {
        if (e.name !== "AbortError") {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === agentMsgId
                ? { ...m, content: "Error: " + e.message, streaming: false }
                : m
            )
          );
        }
      } finally {
        finishStream(agentMsgId);
      }
    },
    [loading, workspace, convId, addMsg, finishStream, createConversation, saveExchange]
  );

  return {
    messages,
    loading,
    send,
    stop,
    addMsg,
    loadConversation,
    createConversation,
    newConversation,
    convId,
  };
}
