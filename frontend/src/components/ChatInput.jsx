import { useState, useRef, useEffect } from "react";
import { Input } from "./ui/input";
import { Button } from "./ui/button";
import { ChevronDown, Check } from "lucide-react";
import { cn } from "../lib/utils";
import { useTranslation } from "react-i18next";

export default function ChatInput({
  value,
  onChange,
  onSend,
  onStop,
  loading,
  disabled,
  modelConfigs,
  activeModelId,
  onModelChange,
}) {
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef(null);
  const { t } = useTranslation();

  useEffect(() => {
    const handleClick = (e) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, []);

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      if (!loading) onSend();
    }
  };

  const activeModel = modelConfigs.find((m) => m.id === activeModelId);
  const displayName = activeModel ? activeModel.name : t("chat.default");

  return (
    <div className="border-t bg-card p-3">
      <div className="flex gap-2 items-end">
        {/* Model selector */}
        <div className="relative" ref={dropdownRef}>
          <button
            type="button"
            className={cn(
              "flex items-center gap-1.5 px-2.5 py-2 rounded-md border text-xs font-medium transition-colors whitespace-nowrap",
              "hover:bg-accent hover:text-accent-foreground",
              activeModelId ? "border-primary/30 bg-primary/5" : "border-border"
            )}
            onClick={() => setDropdownOpen(!dropdownOpen)}
          >
            <span className="max-w-[100px] truncate">{displayName}</span>
            <ChevronDown className={cn("w-3 h-3 transition-transform", dropdownOpen && "rotate-180")} />
          </button>

          {dropdownOpen && (
            <div className="absolute bottom-full left-0 mb-1 w-56 rounded-md border bg-popover shadow-lg z-50">
              <div className="p-1">
                <button
                  className={cn(
                    "w-full flex items-center gap-2 px-3 py-2 rounded-sm text-sm text-left",
                    !activeModelId
                      ? "bg-accent text-accent-foreground"
                      : "hover:bg-accent/50"
                  )}
                  onClick={() => {
                    onModelChange(null);
                    setDropdownOpen(false);
                  }}
                >
                  <span className="flex-1">{t("chat.default")}</span>
                  {!activeModelId && <Check className="w-3.5 h-3.5" />}
                </button>
                {modelConfigs.length > 0 && (
                  <div className="border-t my-1" />
                )}
                {modelConfigs.map((m) => (
                  <button
                    key={m.id}
                    className={cn(
                      "w-full flex items-center gap-2 px-3 py-2 rounded-sm text-sm text-left",
                      activeModelId === m.id
                        ? "bg-accent text-accent-foreground"
                        : "hover:bg-accent/50"
                    )}
                    onClick={() => {
                      onModelChange(m.id);
                      setDropdownOpen(false);
                    }}
                  >
                    <span className="flex-1 truncate">{m.name}</span>
                    <span className="text-xs text-muted-foreground truncate max-w-[80px]">
                      {m.modelName}
                    </span>
                    {activeModelId === m.id && <Check className="w-3.5 h-3.5 flex-shrink-0" />}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Text input */}
        <div className="flex-1 flex gap-2">
          <Input
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={t("chat.placeholder")}
            disabled={loading}
            className="flex-1 font-mono"
          />
          {loading ? (
            <Button variant="destructive" onClick={onStop}>
              {t("chat.stop")}
            </Button>
          ) : (
            <Button onClick={onSend} disabled={disabled || !value.trim()}>
              {t("chat.send")}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
