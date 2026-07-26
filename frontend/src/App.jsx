import { useState, useEffect, useRef, useCallback } from "react";
import { Outlet, useNavigate } from "react-router-dom";
import SettingsDialog from "./components/SettingsDialog";
import ConversationSidebar from "./components/ConversationSidebar";
import useSettings from "./hooks/useSettings";
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
    <div ref={ref} className="relative" onMouseEnter={show} onMouseLeave={hide}>
      <button className="flex items-center gap-2 h-8 px-[11px] text-sm font-medium rounded-md text-foreground/90 hover:bg-accent transition-colors">
        {label}
        <ChevronDown className={cn("w-3 h-3 transition-transform", open && "rotate-180")} />
      </button>
      {open && (
        <div className={cn("absolute top-full mt-1 w-40 rounded-lg border bg-popover shadow-lg py-1 z-50", align === "right" ? "right-0" : "left-0")} onMouseEnter={show} onMouseLeave={hide}>
          {children}
        </div>
      )}
    </div>
  );
}

// ── Language selector ──────────────────────────
const LANGS = [{ key: "zh", labelKey: "lang.zh" }, { key: "en", labelKey: "lang.en" }];
function LanguageSelector() {
  const { i18n, t } = useTranslation();
  const currentLang = i18n.language?.startsWith("zh") ? "zh" : "en";
  const switchLang = (key) => { i18n.changeLanguage(key); try { localStorage.setItem("majo-lang", key); } catch {} };
  return (
    <HeaderDropdown label={<Globe className="w-4 h-4" />} align="right">
      {LANGS.map((l) => (
        <button key={l.key} className="w-full flex items-center justify-between px-3 py-1.5 text-sm hover:bg-accent transition-colors" onClick={() => switchLang(l.key)}>
          {t(l.labelKey)}{currentLang === l.key && <Check className="w-3.5 h-3.5 ml-2" />}
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
function getStoredTheme() { try { return localStorage.getItem("majo-theme"); } catch { return null; } }
function storeTheme(key) { try { localStorage.setItem("majo-theme", key); } catch {} }
function getSystemTheme() { return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"; }
function applyThemeClass(key) {
  const root = document.documentElement;
  const isDark = key === "dark" || (key === "system" && getSystemTheme() === "dark");
  root.classList.toggle("dark", isDark);
}
function ThemeSelector() {
  const { t } = useTranslation();
  const [theme, setTheme] = useState(() => getStoredTheme() || "system");
  useEffect(() => { applyThemeClass(theme); storeTheme(theme); }, [theme]);
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
        <button key={th.key} className="w-full flex items-center gap-2 px-3 py-1.5 text-sm hover:bg-accent transition-colors" onClick={() => setTheme(th.key)}>
          <th.icon className="w-3.5 h-3.5" />{t(th.labelKey)}{theme === th.key && <Check className="w-3.5 h-3.5 ml-auto" />}
        </button>
      ))}
    </HeaderDropdown>
  );
}

export default function App() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const settings = useSettings();
  const convs = useConversations();
  const [showSettings, setShowSettings] = useState(false);

  const handleSave = () => { settings.saveSettings(settings.config); setShowSettings(false); };

  const handleSelectConv = useCallback(async (id) => {
    convs.setActiveId(id);
    navigate("/chat");
  }, [convs, navigate]);

  const handleCreateConv = useCallback(async () => { convs.setActiveId(null); navigate("/chat"); }, [convs, navigate]);
  const handleDeleteConv = useCallback(async (id) => {
    await convs.remove(id);
    if (convs.activeId === id) { convs.setActiveId(null); navigate("/chat"); }
  }, [convs, navigate]);

  const handleDeleteModel = useCallback(async (id) => { await settings.deleteModelConfig(id); }, [settings]);
  const handleMenuNavigate = useCallback((key) => {
    if (key === "models") navigate("/models");
    else if (key === "chat") navigate("/chat");
  }, [navigate]);

  // Context for child routes
  const outletContext = {
    config: settings.config,
    modelConfigs: settings.modelConfigs,
    activeModelId: settings.activeModelId,
    setActiveModelId: settings.setActiveModelId,
    addModelConfig: settings.addModelConfig,
    deleteModelConfig: settings.deleteModelConfig,
    updateModelConfig: settings.updateModelConfig,
    conversations: convs.conversations,
    activeId: convs.activeId,
    onSelectConv: handleSelectConv,
    onCreateConv: handleCreateConv,
    onDeleteConv: handleDeleteConv,
  };

  return (
    <div className="h-screen bg-background text-foreground flex flex-col">
      <header className="h-14 flex items-center justify-between px-7 bg-background flex-shrink-0">
        <div className="flex items-center gap-2">
          <span className="font-bold text-base">Majo</span>
          <span className="text-sm font-normal text-foreground/60">v0.1</span>
        </div>
        <div className="flex items-center gap-4">
          <HeaderDropdown label={t("header.docs")}>
            <a href="#" className="block px-3 py-1.5 text-sm hover:bg-accent transition-colors">{t("header.guide")}</a>
            <a href="#" className="block px-3 py-1.5 text-sm hover:bg-accent transition-colors">{t("header.apiDoc")}</a>
            <a href="#" className="block px-3 py-1.5 text-sm hover:bg-accent transition-colors">{t("header.changelog")}</a>
          </HeaderDropdown>
          <a href="https://github.com" target="_blank" rel="noreferrer" className="flex items-center gap-2 h-8 px-[11px] text-sm font-medium rounded-md text-foreground/90 hover:bg-accent transition-colors">
            <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor"><path d="M12 0C5.37 0 0 5.37 0 12c0 5.3 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61-.546-1.385-1.335-1.755-1.335-1.755-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 21.795 24 17.295 24 12 24 5.37 18.63 0 12 0z"/></svg>
            {t("header.github")}
          </a>
          <div className="w-px h-5 bg-border/50" />
          <button className="flex items-center gap-1.5 h-6 px-2.5 py-1 text-[13px] font-medium rounded-md text-foreground/55 hover:bg-accent transition-colors">
            <Code className="w-3.5 h-3.5" />{t("header.code")}
          </button>
          <div className="w-px h-5 bg-border/50" />
          <LanguageSelector />
          <ThemeSelector />
        </div>
      </header>

      <div className="flex flex-1 min-h-0 gap-3 p-3">
        <ConversationSidebar conversations={convs.conversations} activeId={convs.activeId} onSelect={handleSelectConv} onCreate={handleCreateConv} onDelete={handleDeleteConv} onOpenSettings={() => setShowSettings(true)} onNavigate={handleMenuNavigate} />

        <main className="flex-1 flex flex-col min-w-0 bg-card rounded-xl border border-border overflow-hidden">
          <SettingsDialog open={showSettings} onOpenChange={setShowSettings} config={settings.config} onConfigChange={(partial) => settings.setConfig((prev) => ({ ...prev, ...partial }))} onSave={handleSave} modelConfigs={settings.modelConfigs} onAddModel={settings.addModelConfig} onDeleteModel={handleDeleteModel} />
          <Outlet context={outletContext} />
        </main>
      </div>
    </div>
  );
}
