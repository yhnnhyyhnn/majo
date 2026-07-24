import { Plus, Trash2, MessageSquare } from "lucide-react";
import { Button } from "./ui/button";
import { ScrollArea } from "./ui/scroll-area";
import { cn } from "../lib/utils";

export default function ConversationSidebar({
  conversations,
  activeId,
  onSelect,
  onCreate,
  onDelete,
}) {
  return (
    <aside className="w-56 border-r bg-card flex flex-col h-full">
      {/* Header */}
      <div className="px-3 py-3 border-b">
        <Button
          variant="outline"
          size="sm"
          className="w-full justify-start gap-1.5"
          onClick={onCreate}
        >
          <Plus className="w-4 h-4" />
          New Chat
        </Button>
      </div>

      {/* List */}
      <ScrollArea className="flex-1">
        <div className="p-1">
          {conversations.length === 0 && (
            <p className="text-xs text-muted-foreground text-center py-8 px-2">
              No conversations yet
            </p>
          )}
          {conversations.map((conv) => (
            <div
              key={conv.id}
              onClick={() => onSelect(conv.id)}
              className={cn(
                "group flex items-center gap-2 px-3 py-2 rounded-md cursor-pointer text-sm transition-colors",
                activeId === conv.id
                  ? "bg-accent text-accent-foreground"
                  : "hover:bg-accent/50 text-foreground"
              )}
            >
              <MessageSquare className="w-4 h-4 flex-shrink-0 text-muted-foreground" />
              <span className="truncate flex-1">{conv.title}</span>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onDelete(conv.id);
                }}
                className="opacity-0 group-hover:opacity-100 text-muted-foreground hover:text-destructive transition-opacity"
                title="Delete"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>
            </div>
          ))}
        </div>
      </ScrollArea>
    </aside>
  );
}
