import { Input } from "./ui/input";
import { Button } from "./ui/button";

export default function ChatInput({
  value,
  onChange,
  onSend,
  onStop,
  loading,
  disabled,
}) {
  const handleKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      if (!loading) onSend();
    }
  };

  return (
    <div className="border-t bg-card p-3">
      <div className="flex gap-2">
        <Input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="输入任务描述，Enter 发送..."
          disabled={loading}
          className="flex-1 font-mono"
        />
        {loading ? (
          <Button variant="destructive" onClick={onStop}>
            Stop
          </Button>
        ) : (
          <Button onClick={onSend} disabled={disabled || !value.trim()}>
            Send
          </Button>
        )}
      </div>
    </div>
  );
}
