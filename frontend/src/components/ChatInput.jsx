import { useRef, useEffect } from "react";
import { Button } from "./ui/button";
import { ArrowUp, Mic, Paperclip, ChevronDown } from "lucide-react";
import { useTranslation } from "react-i18next";
import { cn } from "../lib/utils";

export default function ChatInput({
  value,
  onChange,
  onSend,
  onStop,
  loading,
  disabled,
}) {
  const { t } = useTranslation();
  const textareaRef = useRef(null);

  useEffect(() => {
    const el = textareaRef.current;
    if (el) {
      el.style.height = "auto";
      el.style.height = Math.min(el.scrollHeight, 200) + "px";
    }
  }, [value]);

  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      if (!loading && value.trim()) onSend();
    }
  };

  return (
    <div className="px-4 pt-3 pb-2">
      <div className="max-w-3xl mx-auto">
        {/* Sender box */}
        <div className="rounded-lg border bg-card shadow-sm">
          <textarea
            ref={textareaRef}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={t("chat.placeholder")}
            disabled={loading}
            rows={1}
            className="w-full resize-none border-0 bg-transparent px-3 pt-3 pb-0 text-sm font-mono placeholder:text-muted-foreground/40 focus:outline-none disabled:opacity-50"
          />

          {/* Bottom bar */}
          <div className="flex items-center justify-between px-2 pb-2">
            <div className="flex items-center gap-1">
              <button type="button" className="w-8 h-8 flex items-center justify-center rounded-md hover:bg-accent text-muted-foreground transition-colors" title="Voice">
                <Mic className="w-4 h-4" />
              </button>
              <button type="button" className="w-8 h-8 flex items-center justify-center rounded-md hover:bg-accent text-muted-foreground transition-colors" title="Attachment">
                <Paperclip className="w-4 h-4" />
              </button>
              <span className="text-[11px] font-semibold text-muted-foreground/40 px-2">Default</span>
            </div>

            <div className="flex items-center gap-2">
              <span className="text-[11px] text-muted-foreground/40 tabular-nums">{value.length}/10000</span>
              <span className="flex items-center gap-1 text-xs text-muted-foreground/50 cursor-pointer hover:text-muted-foreground transition-colors">
                自动模式 <ChevronDown className="w-3 h-3" />
              </span>
              {loading ? (
                <Button variant="destructive" size="icon" className="w-7 h-7 rounded-lg" onClick={onStop}>
                  <span className="text-[10px]">■</span>
                </Button>
              ) : (
                <button
                  onClick={onSend}
                  disabled={disabled || !value.trim()}
                  className={cn(
                    "w-7 h-7 flex items-center justify-center rounded-lg transition-colors",
                    value.trim()
                      ? "bg-foreground text-background hover:bg-foreground/80"
                      : "bg-muted text-muted-foreground cursor-not-allowed"
                  )}
                >
                  <ArrowUp className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          </div>
        </div>

        {/* Disclaimer */}
        <p className="text-[11px] text-muted-foreground/30 text-center mt-1.5">Majo — AI Coding Agent</p>
      </div>
    </div>
  );
}
