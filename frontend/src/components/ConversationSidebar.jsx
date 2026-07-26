import { useState } from "react";
import {
  Plus, Trash2, MessageSquare, Mail, LayoutGrid, Radio,
  Users, Clock, Activity, FolderOpen, WandSparkles, Wrench,
  Plug, Scan, SlidersHorizontal, BarChart3, Bot, Cpu,
  Globe, Shield, Coins, Save, Mic, Bug, Puzzle, Library,
  ChevronRight, Settings, Menu
} from "lucide-react";
import { cn } from "../lib/utils";
import { useTranslation } from "react-i18next";

// ── Menu item config ────────────────────────────
const MAIN_SECTIONS = [
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
];

const SETTINGS_ITEMS = [
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
];

// ── Reusable components ─────────────────────────
function MenuItem({ item, active, onClick }) {
  const { t } = useTranslation();
  return (
    <button
      className={cn(
        "w-full flex items-center gap-2 h-10 px-2 rounded-lg text-sm font-normal transition-colors",
        active ? "bg-accent text-accent-foreground" : "text-foreground hover:bg-accent/50"
      )}
      onClick={onClick}
    >
      <item.icon className="w-4 h-4 text-muted-foreground" />
      {t(item.labelKey)}
    </button>
  );
}

function SectionHeader({ labelKey, expanded, onToggle }) {
  const { t } = useTranslation();
  return (
    <button
      className="w-full flex items-center h-10 px-2 rounded-lg text-xs font-medium text-muted-foreground/30 hover:text-muted-foreground/50 transition-colors"
      onClick={onToggle}
    >
      <ChevronRight className={cn("w-3 h-3 mr-1 transition-transform", expanded && "rotate-90")} />
      {t(labelKey)}
    </button>
  );
}

export default function ConversationSidebar({
  conversations,
  activeId,
  onSelect,
  onCreate,
  onDelete,
  onOpenSettings,
  onNavigate,
}) {
  const [expanded, setExpanded] = useState(["control", "workspace", "settings"]);
  const [activeMenu, setActiveMenu] = useState("chat");
  const [showConversations, setShowConversations] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const { t } = useTranslation();

  const toggle = (key) =>
    setExpanded((prev) =>
      prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]
    );

  const handleMenuClick = (key) => {
    setActiveMenu(key);
    if (key === "sessions") {
      setShowConversations(!showConversations);
    } else if (key === "models" && onNavigate) {
      onNavigate("models");
    } else if (key === "chat") {
      setShowConversations(false);
      if (onNavigate) onNavigate("chat");
    } else {
      setShowConversations(false);
    }
  };

  const quickItems = [
    { key: "inbox", labelKey: "sidebar.inbox", icon: Mail },
    { key: "apps", labelKey: "sidebar.apps", icon: LayoutGrid },
  ];

  return (
    <aside className={cn(
      "border border-border rounded-xl bg-background flex flex-col flex-shrink-0 select-none h-full overflow-hidden transition-all duration-300",
      collapsed ? "w-14" : "w-52"
    )}>
      <div className="flex-1 overflow-y-auto sidebar-scroll">
        {collapsed ? (
          /* Collapsed: icons only */
          <div className="flex flex-col items-center gap-1 py-2">
            <button onClick={() => { setCollapsed(false); handleMenuClick("chat"); }} className={cn("w-9 h-9 flex items-center justify-center rounded-lg", activeMenu === "chat" ? "bg-accent/60" : "hover:bg-accent/30 text-muted-foreground")}>
              <MessageSquare className="w-4 h-4" />
            </button>
            {quickItems.map((item) => (
              <button key={item.key} onClick={() => handleMenuClick(item.key)} className={cn("w-9 h-9 flex items-center justify-center rounded-lg", activeMenu === item.key ? "bg-accent" : "hover:bg-accent/30 text-muted-foreground")}>
                <item.icon className="w-4 h-4" />
              </button>
            ))}
            <div className="w-6 border-t border-border/30 my-1" />
            {MAIN_SECTIONS.map(s => s.items.map(item => (
              <button key={item.key} onClick={() => handleMenuClick(item.key)} className={cn("w-9 h-9 flex items-center justify-center rounded-lg", activeMenu === item.key ? "bg-accent" : "hover:bg-accent/30 text-muted-foreground")}>
                <item.icon className="w-4 h-4" />
              </button>
            )))}
            <div className="w-6 border-t border-border/30 my-1" />
            {SETTINGS_ITEMS.map(item => (
              <button key={item.key} onClick={() => handleMenuClick(item.key)} className={cn("w-9 h-9 flex items-center justify-center rounded-lg", activeMenu === item.key ? "bg-accent" : "hover:bg-accent/30 text-muted-foreground")}>
                <item.icon className="w-4 h-4" />
              </button>
            ))}
          </div>
        ) : (
          /* Expanded: full menu */
        <div className="px-1 py-0 space-y-0">
          {/* Agent selector */}
          <div className="px-1 pt-2.5 pb-2">
            <div className="text-xs font-medium text-muted-foreground/40 mb-1">
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
              "w-full flex items-center gap-2.5 h-10 px-2 text-sm font-normal rounded-lg transition-colors",
              activeMenu === "chat"
                ? "bg-accent/60 text-foreground"
                : "text-muted-foreground hover:text-foreground hover:bg-accent/30"
            )}
            onClick={() => handleMenuClick("chat")}
          >
            <MessageSquare className="w-4 h-4" />
            {t("sidebar.chat")}
          </button>

          {/* Quick items */}
          {quickItems.map((item) => (
            <MenuItem
              key={item.key}
              item={item}
              active={activeMenu === item.key}
              onClick={() => handleMenuClick(item.key)}
            />
          ))}

          {/* Control + Workspace sections */}
          {MAIN_SECTIONS.map((section) => (
            <div key={section.key}>
              <SectionHeader
                labelKey={section.labelKey}
                expanded={expanded.includes(section.key)}
                onToggle={() => toggle(section.key)}
              />
              {expanded.includes(section.key) &&
                section.items.map((item) => (
                  <div key={item.key}>
                    <MenuItem
                      item={item}
                      active={activeMenu === item.key}
                      onClick={() => handleMenuClick(item.key)}
                    />
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
                            onClick={() => { onSelect(conv.id); setActiveMenu("chat"); }}
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
                              onClick={(e) => { e.stopPropagation(); onDelete(conv.id); }}
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

          {/* Settings section (separated from card) */}
          <div>
            <SectionHeader
              labelKey="sidebar.sections.settings"
              expanded={expanded.includes("settings")}
              onToggle={() => toggle("settings")}
            />
            {expanded.includes("settings") &&
              SETTINGS_ITEMS.map((item) => (
                <MenuItem
                  key={item.key}
                  item={item}
                  active={activeMenu === item.key}
                  onClick={() => handleMenuClick(item.key)}
                />
              ))}
          </div>
        </div>
        )}
      </div>

      {/* ── Bottom actions ── */}
      <div className={cn(
        "h-12 flex items-center gap-1 px-2 flex-shrink-0",
        collapsed ? "justify-center" : "justify-end"
      )}>
        {!collapsed && (
          <button
            className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
            onClick={onOpenSettings}
            title="Settings"
          >
            <Settings className="w-4 h-4" />
          </button>
        )}
        <button
          className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
          onClick={() => setCollapsed(!collapsed)}
          title={collapsed ? "展开" : "收起"}
        >
          <Menu className="w-4 h-4" />
        </button>
      </div>
    </aside>
  );
}
