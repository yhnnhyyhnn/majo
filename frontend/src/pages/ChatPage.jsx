import { useState, useEffect, useRef, useCallback } from "react";
import { ScrollArea } from "../components/ui/scroll-area";
import ChatMessage from "../components/ChatMessage";
import ChatInput from "../components/ChatInput";
import { ChevronDown, Check } from "lucide-react";
import { cn } from "../lib/utils";
import { useTranslation } from "react-i18next";
import useChat from "../hooks/useChat";
import { useOutletContext } from "react-router-dom";

// ── Model selector for top bar ─────────────────
function ModelSelector({ modelConfigs, activeModelId, onChange }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, []);

  const active = modelConfigs.find((m) => m.id === activeModelId);

  return (
    <div ref={ref} className="relative">
      <button onClick={() => setOpen(!open)} className="flex items-center gap-1.5 h-[30px] px-2.5 text-sm font-medium rounded-md hover:bg-accent transition-colors">
        <span className="max-w-[120px] truncate">{active ? active.name : t("chat.selectModel")}</span>
        <ChevronDown className={cn("w-3 h-3 transition-transform", open && "rotate-180")} />
      </button>
      {open && (
        <div className="absolute right-0 top-full mt-1 w-52 rounded-lg border bg-popover shadow-lg py-1 z-50">
          <button className={cn("w-full flex items-center justify-between px-3 py-1.5 text-sm text-left", !activeModelId ? "bg-accent" : "hover:bg-accent/50")} onClick={() => { onChange(null); setOpen(false); }}>
            {t("chat.default")}
            {!activeModelId && <Check className="w-3.5 h-3.5" />}
          </button>
          {modelConfigs.length > 0 && <div className="border-t my-1" />}
          {modelConfigs.map((m) => (
            <button key={m.id} className={cn("w-full flex items-center justify-between px-3 py-1.5 text-sm text-left", activeModelId === m.id ? "bg-accent" : "hover:bg-accent/50")} onClick={() => { onChange(m.id); setOpen(false); }}>
              <span className="truncate">{m.name}</span>
              <span className="text-xs text-muted-foreground ml-2 truncate max-w-[80px]">{m.modelName}</span>
              {activeModelId === m.id && <Check className="w-3.5 h-3.5 ml-1 flex-shrink-0" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export default function ChatPage() {
  const { t } = useTranslation();
  const { config, modelConfigs, activeModelId, setActiveModelId, conversations, activeId, onSelectConv, onCreateConv, onDeleteConv } = useOutletContext();
  const chat = useChat(config.workspace, activeModelId);
  const [input, setInput] = useState("");
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [chat.messages]);

  const handleSend = () => {
    if (!input.trim() || chat.loading) return;
    chat.send(input);
    setInput("");
  };

  return (
    <>
      {/* Top bar with chat title */}
      <div className="h-[54px] flex items-center px-5 flex-shrink-0">
        <div className="flex items-center gap-3 flex-1 min-w-0">
          <span className="text-base font-medium truncate">
            {activeId ? conversations.find((c) => c.id === activeId)?.title || "Chat" : "New Chat"}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <ModelSelector modelConfigs={modelConfigs} activeModelId={activeModelId} onChange={setActiveModelId} />
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-hidden">
        <ScrollArea className="h-full">
          <div className="max-w-3xl mx-auto px-4 py-6">
            {chat.messages.length === 0 && (
              <div className="flex flex-col items-center justify-center min-h-[300px] text-center">
                <p className="text-lg text-foreground mb-1">{t("chat.welcome")}</p>
                <p className="text-sm text-muted-foreground">{t("chat.welcomeDesc")}</p>
              </div>
            )}
            {chat.messages.map((m) => (
              <ChatMessage key={m.id} message={m} />
            ))}
            <div ref={bottomRef} />
          </div>
        </ScrollArea>
      </div>

      {/* Input */}
      <ChatInput value={input} onChange={setInput} onSend={handleSend} onStop={chat.stop} loading={chat.loading} disabled={!input.trim()} />
    </>
  );
}
