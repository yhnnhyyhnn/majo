import { marked } from "marked";
import { Card } from "./ui/card";
import { cn } from "../lib/utils";
import { useTranslation } from "react-i18next";

marked.setOptions({ breaks: true, gfm: true });

const toolIcon = "🔧";

export default function ChatMessage({ message }) {
  const { role, content, time, streaming, thinkingBlocks, toolCalls } =
    message;
  const isUser = role === "user";
  const isStreaming = streaming && !content;
  const { t } = useTranslation();

  return (
    <div
      className={cn(
        "flex gap-3 mb-5 items-start",
        isUser && "flex-row-reverse"
      )}
    >
      {/* Avatar */}
      <div
        className={cn(
          "rounded-full w-8 h-8 flex items-center justify-center text-lg flex-shrink-0",
          isUser
            ? "bg-primary text-primary-foreground"
            : "bg-emerald-500/20 text-emerald-400"
        )}
      >
        {isUser ? (
          "👤"
        ) : (
          <img src="/majo-icon.svg" alt="Majo" className="w-full h-full rounded-full object-cover" />
        )}
      </div>

      {/* Bubble */}
      <Card
        className={cn(
          "max-w-[75%] px-4 py-3",
          isUser
            ? "bg-primary text-primary-foreground rounded-tr-sm"
            : "bg-card rounded-tl-sm"
        )}
      >
        {/* Header */}
        <div className="text-xs text-muted-foreground mb-1.5">
          {isUser ? t("chat.you") : t("chat.agent")} · {time}
          {streaming && !isUser && (
            <span className="text-emerald-400 ml-2">● {t("chat.streaming")}</span>
          )}
        </div>

        {/* Thinking blocks */}
        {thinkingBlocks?.length > 0 && (
          <div className="mb-1.5 space-y-1.5">
            {thinkingBlocks.map((b, i) => (
              <div
                key={b.id || i}
                className="text-xs text-muted-foreground italic whitespace-pre-wrap border-l-2 border-border pl-2.5 py-1 bg-muted/30 rounded-r"
              >
                {b.text}
              </div>
            ))}
          </div>
        )}

        {/* Content */}
        <div className="leading-relaxed break-words">
          {content ? (
            isUser ? (
              <span className="whitespace-pre-wrap">{content}</span>
            ) : (
              <div
                className="prose prose-sm dark:prose-invert max-w-none"
                dangerouslySetInnerHTML={{ __html: marked.parse(content) }}
              />
            )
          ) : isStreaming ? (
            <span className="text-muted-foreground italic">{t("chat.thinking")}</span>
          ) : null}
        </div>

        {/* Tool calls */}
        {toolCalls?.length > 0 && (
          <div className="mt-3 pt-2 border-t border-border space-y-1.5">
            {toolCalls.map((tc, i) => (
              <div
                key={tc.id || i}
                className="bg-muted/50 border border-border rounded-md px-3 py-2 text-sm"
              >
                <div className="flex items-center gap-1.5 mb-0.5">
                  <span className="text-amber-400">{toolIcon}</span>
                  <span className="text-amber-400 font-medium">{tc.name}</span>
                  {tc.result == null && (
                    <span className="text-muted-foreground text-xs">
                      executing...
                    </span>
                  )}
                </div>
                {tc.args && (
                  <div className="text-muted-foreground text-xs font-mono opacity-80">
                    {tc.args.length > 120
                      ? tc.args.slice(0, 120) + "..."
                      : tc.args}
                  </div>
                )}
                {tc.result != null && (
                  <div className="text-teal-400 text-xs font-mono mt-1 max-h-28 overflow-auto whitespace-pre-wrap break-all">
                    {tc.result.length > 300
                      ? tc.result.slice(0, 300) + "..."
                      : tc.result}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
