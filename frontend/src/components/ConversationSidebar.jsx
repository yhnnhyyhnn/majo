import { useState } from "react";
import {
  Plus, Trash2, MessageSquare, Mail, LayoutGrid, Radio,
  Users, Clock, Activity, FolderOpen, WandSparkles, Wrench,
  Plug, Scan, SlidersHorizontal, BarChart3, Bot, Cpu,
  Globe, Shield, Coins, Save, Mic, Bug, Puzzle, Library,
  ChevronRight, Settings, PanelRightClose
} from "lucide-react";
import { ScrollArea } from "./ui/scroll-area";
import { cn } from "../lib/utils";
import { useTranslation } from "react-i18next";

const SECTIONS = [
  {
    key: "control",
    labelKey: "sidebar.sections.control",
    items: [
      { key: "channels", labelKey: "sidebar.items.channels", icon: Radio },
      { key: "sessions", labelKey: "sidebar.items.sessions", icon: Users },
      { key: "schedules", labelKey: "sidebar.items.schedules", icon: Clock },
      { key: "heartbeat", labelKey: "sidebar.items.heartbeat", icon: Activity },
    ],
  },
  {
    key: "workspace",
    labelKey: "sidebar.sections.workspace",
    items: [
      { key: "files", labelKey: "sidebar.items.files", icon: FolderOpen },
      { key: "skills", labelKey: "sidebar.items.skills", icon: WandSparkles },
      { key: "tools", labelKey: "sidebar.items.tools", icon: Wrench },
      { key: "mcp", labelKey: "sidebar.items.mcp", icon: Plug },
      { key: "acp", labelKey: "sidebar.items.acp", icon: Scan },
      { key: "runConfig", labelKey: "sidebar.items.runConfig", icon: SlidersHorizontal },
      { key: "stats", labelKey: "sidebar.items.stats", icon: BarChart3 },
    ],
  },
  {
    key: "settings",
    labelKey: "sidebar.sections.settings",
    items: [
      { key: "agentMgmt", labelKey: "sidebar.items.agentMgmt", icon: Bot },
      { key: "models", labelKey: "sidebar.items.models", icon: Cpu },
      { key: "skillPool", labelKey: "sidebar.items.skillPool", icon: Library },
      { key: "envVars", labelKey: "sidebar.items.envVars", icon: Globe },
      { key: "security", labelKey: "sidebar.items.security", icon: Shield },
      { key: "tokens", labelKey: "sidebar.items.tokens", icon: Coins },
      { key: "backup", labelKey: "sidebar.items.backup", icon: Save },
      { key: "voice", labelKey: "sidebar.items.voice", icon: Mic },
      { key: "debug", labelKey: "sidebar.items.debug", icon: Bug },
      { key: "plugins", labelKey: "sidebar.items.plugins", icon: Puzzle },
    ],
  },
];

export default function ConversationSidebar({
  conversations,
  activeId,
  onSelect,
  onCreate,
  onDelete,
  onOpenSettings,
}) {
  const [expanded, setExpanded] = useState(["control", "workspace", "settings"]);
  const [activeMenu, setActiveMenu] = useState("chat");
  const [showConversations, setShowConversations] = useState(false);
  const { t } = useTranslation();

  const toggle = (key) =>
    setExpanded((prev) =>
      prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]
    );

  const handleMenuClick = (key) => {
    setActiveMenu(key);
    if (key === "sessions") {
      setShowConversations(!showConversations);
    } else {
      setShowConversations(false);
    }
  };

  return (
    <aside className="w-60 border-r border-border bg-card flex flex-col flex-shrink-0 select-none">
      {/* ── Agent selector ── */}
      <div className="mx-3 mt-3 mb-1 rounded-xl rounded-b-none bg-muted/40">
        <div className="px-3 pt-3 pb-2">
          <div className="text-[11px] font-medium text-muted-foreground/40 mb-1">
            {t("sidebar.currentAgent")} (1)
          </div>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1.5">
              <div className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
              <span className="text-sm">Majo</span>
            </div>
            <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/40" />
          </div>
        </div>
        {/* 聊天 tab */}
        <button
          className={cn(
            "w-full flex items-center gap-2 h-10 px-3 text-sm font-normal transition-colors",
            activeMenu === "chat"
              ? "bg-white dark:bg-accent text-foreground"
              : "text-muted-foreground hover:text-foreground"
          )}
          onClick={() => handleMenuClick("chat")}
        >
          <MessageSquare className="w-4 h-4" />
          {t("sidebar.chat")}
        </button>
      </div>

      {/* ── Quick menu items ── */}
      <div className="px-3 mt-1">
        {[
          { key: "inbox", labelKey: "sidebar.inbox", icon: Mail },
          { key: "apps", labelKey: "sidebar.apps", icon: LayoutGrid },
        ].map((item) => (
          <button
            key={item.key}
            className={cn(
              "w-full flex items-center gap-2 h-10 px-2 rounded-lg text-sm font-normal transition-colors",
              activeMenu === item.key
                ? "bg-accent text-accent-foreground"
                : "text-foreground hover:bg-accent/50"
            )}
            onClick={() => handleMenuClick(item.key)}
          >
            <item.icon className="w-4 h-4 text-muted-foreground" />
            {t(item.labelKey)}
          </button>
        ))}
      </div>

      {/* ── Sections ── */}
      <ScrollArea className="flex-1">
        <div className="px-3 pb-2">
          {SECTIONS.map((section) => (
            <div key={section.key} className="mt-1">
              {/* Section header */}
              <button
                className="w-full flex items-center h-10 px-2 text-[11px] font-medium text-muted-foreground/40 hover:text-muted-foreground/60 transition-colors"
                onClick={() => toggle(section.key)}
              >
                <ChevronRight
                  className={cn(
                    "w-3 h-3 mr-1 transition-transform",
                    expanded.includes(section.key) && "rotate-90"
                  )}
                />
                {t(section.labelKey)}
              </button>

              {/* Section items */}
              {expanded.includes(section.key) &&
                section.items.map((item) => (
                  <div key={item.key}>
                    <button
                      className={cn(
                        "w-full flex items-center gap-2 h-10 px-2 rounded-lg text-sm font-normal transition-colors",
                        activeMenu === item.key
                          ? "bg-accent text-accent-foreground"
                          : "text-foreground hover:bg-accent/50"
                      )}
                      onClick={() => handleMenuClick(item.key)}
                    >
                      <item.icon className="w-4 h-4 text-muted-foreground" />
                      {t(item.labelKey)}
                    </button>

                    {/* Conversation list under 会话 */}
                    {item.key === "sessions" && showConversations && (
                      <div className="ml-2 pl-4 border-l border-border/50 mt-0.5 mb-1">
                        <button
                          className="w-full flex items-center gap-2 h-9 pl-2 pr-2 rounded-lg text-xs text-muted-foreground hover:text-foreground hover:bg-accent/30 transition-colors"
                          onClick={onCreate}
                        >
                          <Plus className="w-3 h-3" />
                          {t("sidebar.newChat")}
                        </button>
                        {conversations.length === 0 && (
                          <p className="text-[10px] text-muted-foreground/40 text-center py-3">
                            {t("sidebar.noConversations")}
                          </p>
                        )}
                        {conversations.map((conv) => (
                          <div
                            key={conv.id}
                            onClick={() => {
                              onSelect(conv.id);
                              setActiveMenu("chat");
                            }}
                            className={cn(
                              "group flex items-center gap-1.5 h-9 pl-2 pr-1 rounded-lg cursor-pointer text-xs transition-colors",
                              activeId === conv.id
                                ? "bg-accent text-accent-foreground"
                                : "text-foreground hover:bg-accent/50"
                            )}
                          >
                            <MessageSquare className="w-3 h-3 flex-shrink-0 text-muted-foreground" />
                            <span className="truncate flex-1">{conv.title}</span>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                onDelete(conv.id);
                              }}
                              className="opacity-0 group-hover:opacity-100 text-muted-foreground hover:text-destructive transition-opacity p-0.5"
                            >
                              <Trash2 className="w-3 h-3" />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
            </div>
          ))}
        </div>
      </ScrollArea>

      {/* ── Bottom actions ── */}
      <div className="px-3 pb-3 pt-2 flex justify-end gap-1 border-t border-border/50">
        <button
          className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
          onClick={onOpenSettings}
          title="Settings"
        >
          <Settings className="w-4 h-4" />
        </button>
        <button
          className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
          title="Collapse"
        >
          <PanelRightClose className="w-4 h-4" />
        </button>
      </div>
    </aside>
  );
}
