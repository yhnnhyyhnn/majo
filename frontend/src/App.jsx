import { useState, useEffect, useRef } from "react";
import { ScrollArea } from "./components/ui/scroll-area";
import { Button } from "./components/ui/button";
import ChatMessage from "./components/ChatMessage";
import ChatInput from "./components/ChatInput";
import SettingsDialog from "./components/SettingsDialog";
import useSettings from "./hooks/useSettings";
import useChat from "./hooks/useChat";
import { Settings } from "lucide-react";

export default function App() {
  const { config, setConfig, saveSettings } = useSettings();
  const { messages, loading, send, stop } = useChat(config);
  const [input, setInput] = useState("");
  const [showSettings, setShowSettings] = useState(false);
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const handleSend = () => {
    if (!input.trim() || loading) return;
    send(input);
    setInput("");
  };

  const handleSave = () => {
    saveSettings(config);
    setShowSettings(false);
  };

  return (
    <div className="min-h-screen bg-background text-foreground flex flex-col">
      {/* Header */}
      <header className="border-b bg-card px-4 py-2 flex items-center justify-between">
        <span className="font-bold text-lg">Majo</span>
        <Button variant="ghost" size="sm" onClick={() => setShowSettings(true)}>
          <Settings className="w-4 h-4 mr-1.5" />
          Settings
        </Button>
      </header>

      {/* Settings Dialog */}
      <SettingsDialog
        open={showSettings}
        onOpenChange={setShowSettings}
        config={config}
        onConfigChange={(partial) =>
          setConfig((prev) => ({ ...prev, ...partial }))
        }
        onSave={handleSave}
      />

      {/* Messages */}
      <main className="flex-1 overflow-hidden">
        <ScrollArea className="h-full">
          <div className="p-4">
            {messages.map((m) => (
              <ChatMessage key={m.id} message={m} />
            ))}
            <div ref={bottomRef} />
          </div>
        </ScrollArea>
      </main>

      {/* Input */}
      <ChatInput
        value={input}
        onChange={setInput}
        onSend={handleSend}
        onStop={stop}
        loading={loading}
        disabled={!input.trim()}
      />
    </div>
  );
}
