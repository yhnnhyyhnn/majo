import { useState, useEffect, useRef, useCallback } from "react";
import { ScrollArea } from "./components/ui/scroll-area";
import ChatMessage from "./components/ChatMessage";
import ChatInput from "./components/ChatInput";
import SettingsDialog from "./components/SettingsDialog";
import ConversationSidebar from "./components/ConversationSidebar";
import ModelsPage from "./components/ModelsPage";
import useSettings from "./hooks/useSettings";
import useChat from "./hooks/useChat";
import useConversations from "./hooks/useConversations";
import { Code, Globe, ChevronDown, Check, Sun, Moon, Monitor } from "lucide-react";
import { cn } from "./lib/utils";
import { useTranslation } from "react-i18next";

// ── Header dropdown ────────────────────────────
function HeaderDropdown({ label, children, align = "left" }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  const timeout = useRef(null);

  const show = () => { clearTimeout(timeout.current); setOpen(true); };
  const hide = () => { timeout.current = setTimeout(() => setOpen(false), 150); };

  return (
    <div
      ref={ref}
      className="relative"
      onMouseEnter={show}
      onMouseLeave={hide}
    >
      <button className="flex items-center gap-2 h-8 px-[11px] text-sm font-medium rounded-md text-foreground/90 hover:bg-accent transition-colors">
        {label}
        <ChevronDown className={cn("w-3 h-3 transition-transform", open && "rotate-180")} />
      </button>
      {open && (
        <div
          className={cn(
            "absolute top-full mt-1 w-40 rounded-lg border bg-popover shadow-lg py-1 z-50",
            align === "right" ? "right-0" : "left-0"
          )}
          onMouseEnter={show}
          onMouseLeave={hide}
        >
          {children}
        </div>
      )}
    </div>
  );
}

// ── Language selector ──────────────────────────
const LANGS = [
  { key: "zh", labelKey: "lang.zh" },
  { key: "en", labelKey: "lang.en" },
];

function LanguageSelector() {
  const { i18n, t } = useTranslation();
  const currentLang = i18n.language?.startsWith("zh") ? "zh" : "en";

  const switchLang = (key) => {
    i18n.changeLanguage(key);
    try { localStorage.setItem("majo-lang", key); } catch {}
  };

  return (
    <HeaderDropdown label={<Globe className="w-4 h-4" />} align="right">
      {LANGS.map((l) => (
        <button
          key={l.key}
          className="w-full flex items-center justify-between px-3 py-1.5 text-sm hover:bg-accent transition-colors"
          onClick={() => switchLang(l.key)}
        >
          {t(l.labelKey)}
          {currentLang === l.key && <Check className="w-3.5 h-3.5 ml-2" />}
        </button>
      ))}
    </HeaderDropdown>
  );
}

// ── Theme selector ────────────────────────────
const THEMES = [
  { key: "light", labelKey: "theme.light", icon: Sun },
  { key: "dark", labelKey: "theme.dark", icon: Moon },
  { key: "system", labelKey: "theme.system", icon: Monitor },
];

function getStoredTheme() {
  try { return localStorage.getItem("majo-theme"); } catch { return null; }
}
function storeTheme(key) {
  try { localStorage.setItem("majo-theme", key); } catch {}
}

function getSystemTheme() {
  if (typeof window === "undefined") return "dark";
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function applyThemeClass(key) {
  const root = document.documentElement;
  const isDark = key === "dark" || (key === "system" && getSystemTheme() === "dark");
  root.classList.toggle("dark", isDark);
}

function ThemeSelector() {
  const { t } = useTranslation();
  const [theme, setTheme] = useState(() => {
    const stored = getStoredTheme();
    return stored || "system";
  });

  // Apply theme on mount + re-apply on change
  useEffect(() => {
    applyThemeClass(theme);
    storeTheme(theme);
  }, [theme]);

  // Listen for system preference changes when in "system" mode
  useEffect(() => {
    if (theme !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = () => applyThemeClass("system");
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, [theme]);

  const Icon = THEMES.find((th) => th.key === theme)?.icon || Sun;

  return (
    <HeaderDropdown label={<Icon className="w-4 h-4" />} align="right">
      {THEMES.map((th) => (
        <button
          key={th.key}
          className="w-full flex items-center gap-2 px-3 py-1.5 text-sm hover:bg-accent transition-colors"
          onClick={() => setTheme(th.key)}
        >
          <th.icon className="w-3.5 h-3.5" />
          {t(th.labelKey)}
          {theme === th.key && <Check className="w-3.5 h-3.5 ml-auto" />}
        </button>
      ))}
    </HeaderDropdown>
  );
}

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
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1.5 h-[30px] px-2.5 text-sm font-medium rounded-md hover:bg-accent transition-colors"
      >
        <span className="max-w-[120px] truncate">
          {active ? active.name : t("chat.selectModel")}
        </span>
        <ChevronDown className={cn("w-3 h-3 transition-transform", open && "rotate-180")} />
      </button>
      {open && (
        <div className="absolute right-0 top-full mt-1 w-52 rounded-lg border bg-popover shadow-lg py-1 z-50">
          <button
            className={cn(
              "w-full flex items-center justify-between px-3 py-1.5 text-sm text-left",
              !activeModelId ? "bg-accent" : "hover:bg-accent/50"
            )}
            onClick={() => { onChange(null); setOpen(false); }}
          >
            {t("chat.default")}
            {!activeModelId && <Check className="w-3.5 h-3.5" />}
          </button>
          {modelConfigs.length > 0 && <div className="border-t my-1" />}
          {modelConfigs.map((m) => (
            <button
              key={m.id}
              className={cn(
                "w-full flex items-center justify-between px-3 py-1.5 text-sm text-left",
                activeModelId === m.id ? "bg-accent" : "hover:bg-accent/50"
              )}
              onClick={() => { onChange(m.id); setOpen(false); }}
            >
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

export default function App() {
  const { t } = useTranslation();
  const {
    config,
    setConfig,
    saveSettings,
    modelConfigs,
    addModelConfig,
    deleteModelConfig,
    updateModelConfig,
    activeModelId,
    setActiveModelId,
  } = useSettings();
  const chat = useChat(config.workspace, activeModelId);
  const convs = useConversations();
  const [input, setInput] = useState("");
  const [showSettings, setShowSettings] = useState(false);
  const [activePage, setActivePage] = useState("chat");
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

  const handleDeleteModel = useCallback(
    async (id) => {
      await deleteModelConfig(id);
    },
    [deleteModelConfig]
  );

  const handleMenuNavigate = useCallback((key) => {
    if (key === "models") {
      setActivePage("models");
    } else if (key === "chat") {
      setActivePage("chat");
    }
  }, []);

  useEffect(() => {
    if (!chat.loading && chat.convId && chat.messages.length > 0) {
      convs.load();
    }
  }, [chat.loading]);

  return (
    <div className="h-screen bg-background text-foreground flex flex-col">
      {/* ====== Top Banner (matches reference: 56px) ====== */}
      <header className="h-14 flex items-center justify-between px-7 bg-background flex-shrink-0">
        {/* Left: Logo + version */}
        <div className="flex items-center gap-2">
          <span className="font-bold text-base">Majo</span>
          <span className="text-sm font-normal text-foreground/60">
            v0.1
          </span>
        </div>

        {/* Right: action buttons */}
        <div className="flex items-center gap-4">
          {/* 文档资料 */}
          <HeaderDropdown label={t("header.docs")}>
            <a href="#" className="block px-3 py-1.5 text-sm hover:bg-accent transition-colors">{t("header.guide")}</a>
            <a href="#" className="block px-3 py-1.5 text-sm hover:bg-accent transition-colors">{t("header.apiDoc")}</a>
            <a href="#" className="block px-3 py-1.5 text-sm hover:bg-accent transition-colors">{t("header.changelog")}</a>
          </HeaderDropdown>

          {/* GitHub */}
          <a
            href="https://github.com"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-2 h-8 px-[11px] text-sm font-medium rounded-md text-foreground/90 hover:bg-accent transition-colors"
          >
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 0C5.37 0 0 5.37 0 12c0 5.3 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61-.546-1.385-1.335-1.755-1.335-1.755-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 21.795 24 17.295 24 12 24 5.37 18.63 0 12 0z"/>
            </svg>
            {t("header.github")}
          </a>

          <div className="w-px h-5 bg-border/50" />

          {/* 代码 */}
          <button className="flex items-center gap-1.5 h-6 px-2.5 py-1 text-[13px] font-medium rounded-md text-foreground/55 hover:bg-accent transition-colors">
            <Code className="w-3.5 h-3.5" />
            {t("header.code")}
          </button>

          <div className="w-px h-5 bg-border/50" />

          {/* 语种选择 */}
          <LanguageSelector />

          {/* 主题选择 */}
          <ThemeSelector />
        </div>
      </header>

      {/* ====== Body: sidebar + main ====== */}
      <div className="flex flex-1 min-h-0 gap-3 p-3">
        {/* Sidebar */}
        <ConversationSidebar
          conversations={convs.conversations}
          activeId={convs.activeId}
          onSelect={handleSelectConv}
          onCreate={handleCreateConv}
          onDelete={handleDeleteConv}
          onOpenSettings={() => setShowSettings(true)}
          onNavigate={handleMenuNavigate}
        />

        {/* Main area */}
        <main className="flex-1 flex flex-col min-w-0 bg-card rounded-xl border border-border overflow-hidden">
          {/* Settings Dialog (always available) */}
          <SettingsDialog
            open={showSettings}
            onOpenChange={setShowSettings}
            config={config}
            onConfigChange={(partial) =>
              setConfig((prev) => ({ ...prev, ...partial }))
            }
            onSave={handleSave}
            modelConfigs={modelConfigs}
            onAddModel={addModelConfig}
            onDeleteModel={handleDeleteModel}
          />

          {activePage === "models" ? (
            <ModelsPage
              modelConfigs={modelConfigs}
              onAdd={addModelConfig}
              onDelete={deleteModelConfig}
              onUpdate={updateModelConfig}
              onBack={() => setActivePage("chat")}
            />
          ) : (
            <>
              {/* Top bar with chat title */}
              <div className="h-[54px] flex items-center px-5 flex-shrink-0">
                <div className="flex items-center gap-3 flex-1 min-w-0">
                  <span className="text-base font-medium truncate">
                    {convs.activeId
                      ? convs.conversations.find((c) => c.id === convs.activeId)?.title || "Chat"
                      : "New Chat"}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  {/* Model selector in top bar */}
                  <ModelSelector
                    modelConfigs={modelConfigs}
                    activeModelId={activeModelId}
                    onChange={setActiveModelId}
                  />
                </div>
              </div>

              {/* Messages */}
              <div className="flex-1 overflow-hidden">
                <ScrollArea className="h-full">
                  <div className="max-w-3xl mx-auto px-4 py-6">
                    {chat.messages.length === 0 && (
                      <div className="flex flex-col items-center justify-center min-h-[300px] text-center">
                        <p className="text-lg text-foreground mb-1">
                          {t("chat.welcome")}
                        </p>
                        <p className="text-sm text-muted-foreground">
                          {t("chat.welcomeDesc")}
                        </p>
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
              <ChatInput
                value={input}
                onChange={setInput}
                onSend={handleSend}
                onStop={chat.stop}
                loading={chat.loading}
                disabled={!input.trim()}
              />
            </>
          )}
        </main>
      </div>
    </div>
  );
}
