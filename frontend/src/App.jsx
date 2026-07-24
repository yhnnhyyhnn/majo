import { useState, useEffect, useRef, useCallback } from "react";
import { ScrollArea } from "./components/ui/scroll-area";
import { Button } from "./components/ui/button";
import ChatMessage from "./components/ChatMessage";
import ChatInput from "./components/ChatInput";
import SettingsDialog from "./components/SettingsDialog";
import ConversationSidebar from "./components/ConversationSidebar";
import useSettings from "./hooks/useSettings";
import useChat from "./hooks/useChat";
import useConversations from "./hooks/useConversations";
import { Settings } from "lucide-react";

export default function App() {
  const { config, setConfig, saveSettings } = useSettings();
  const chat = useChat(config.workspace);
  const convs = useConversations();
  const [input, setInput] = useState("");
  const [showSettings, setShowSettings] = useState(false);
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [chat.messages]);

  const handleSend = () => {
    if (!input.trim() || chat.loading) return;
    chat.send(input);
    setInput("");
  };

  const handleSave = () => {
    saveSettings(config);
    setShowSettings(false);
  };

  const handleSelectConv = useCallback(
    async (id) => {
      convs.setActiveId(id);
      await chat.loadConversation(id);
    },
    [convs, chat]
  );

  const handleCreateConv = useCallback(async () => {
    chat.newConversation();
    convs.setActiveId(null);
  }, [chat, convs]);

  const handleDeleteConv = useCallback(
    async (id) => {
      await convs.remove(id);
      if (convs.activeId === id) {
        chat.newConversation();
      }
    },
    [convs, chat]
  );

  // Refresh conversation list after each exchange
  useEffect(() => {
    if (!chat.loading && chat.convId && chat.messages.length > 0) {
      convs.load();
    }
  }, [chat.loading]);

  return (
    <div className="min-h-screen bg-background text-foreground flex">
      {/* Sidebar */}
      <ConversationSidebar
        conversations={convs.conversations}
        activeId={convs.activeId}
        onSelect={handleSelectConv}
        onCreate={handleCreateConv}
        onDelete={handleDeleteConv}
      />

      {/* Main area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Header */}
        <header className="border-b bg-card px-4 py-2 flex items-center justify-between">
          <span className="font-bold text-lg">Majo</span>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setShowSettings(true)}
          >
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
              {chat.messages.map((m) => (
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
          onStop={chat.stop}
          loading={chat.loading}
          disabled={!input.trim()}
        />
      </div>
    </div>
  );
}
