import { useState, useEffect, useCallback } from "react";

const API = "/api";

export default function useConversations() {
  const [conversations, setConversations] = useState([]);
  const [activeId, setActiveId] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch(API + "/conversations");
      const data = await res.json();
      setConversations(data);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, []);

  const create = useCallback(async () => {
    try {
      const res = await fetch(API + "/conversations", { method: "POST" });
      const data = await res.json();
      setActiveId(data.id);
      await load();
      return data.id;
    } catch {
      return null;
    }
  }, [load]);

  const remove = useCallback(
    async (id) => {
      try {
        await fetch(API + "/conversations/" + id, { method: "DELETE" });
        if (activeId === id) setActiveId(null);
        await load();
      } catch {
        // ignore
      }
    },
    [activeId, load]
  );

  const loadMessages = useCallback(async (id) => {
    try {
      const res = await fetch(API + "/conversations/" + id + "/messages");
      const data = await res.json();
      return data.map((m) => {
        let metadata = {};
        try {
          metadata = m.metadata ? JSON.parse(m.metadata) : {};
        } catch {
          // ignore
        }
        return {
          id: m.id,
          role: m.role,
          content: m.content,
          time: m.time ? new Date(m.time).toLocaleTimeString() : "",
          streaming: false,
          thinkingBlocks: metadata.thinkingBlocks || [],
          toolCalls: metadata.toolCalls || [],
        };
      });
    } catch {
      return [];
    }
  }, []);

  const saveMessages = useCallback(
    async (id, messages) => {
      const payload = messages.map((m) => ({
        role: m.role,
        content: m.content,
        metadata: JSON.stringify({
          thinkingBlocks: m.thinkingBlocks || [],
          toolCalls: m.toolCalls || [],
        }),
      }));
      try {
        await fetch(API + "/conversations/" + id + "/messages", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        });
        await load();
      } catch {
        // ignore
      }
    },
    [load]
  );

  useEffect(() => {
    load();
  }, [load]);

  return {
    conversations,
    activeId,
    setActiveId,
    loading,
    load,
    create,
    remove,
    loadMessages,
    saveMessages,
  };
}
