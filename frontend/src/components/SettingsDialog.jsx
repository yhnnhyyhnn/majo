import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "./ui/dialog";
import { Input } from "./ui/input";
import { Button } from "./ui/button";

const FIELD_CLASS = "flex flex-col gap-1.5";

export default function SettingsDialog({
  open,
  onOpenChange,
  config,
  onConfigChange,
  onSave,
}) {
  const update = (key) => (e) =>
    onConfigChange({ [key]: e.target.value });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Settings</DialogTitle>
          <DialogDescription>
            配置大模型连接参数，保存后立即生效。
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className={FIELD_CLASS}>
            <label className="text-sm font-medium">API URL</label>
            <Input
              value={config.baseUrl}
              onChange={update("baseUrl")}
              placeholder="https://api.openai.com/v1"
            />
          </div>
          <div className={FIELD_CLASS}>
            <label className="text-sm font-medium">API Key</label>
            <Input
              type="password"
              value={config.apiKey}
              onChange={update("apiKey")}
              placeholder="sk-xxxxxxxx"
            />
          </div>
          <div className={FIELD_CLASS}>
            <label className="text-sm font-medium">Model</label>
            <Input
              value={config.modelName}
              onChange={update("modelName")}
              placeholder="gpt-4o-mini"
            />
          </div>
          <div className={FIELD_CLASS}>
            <label className="text-sm font-medium">Workspace</label>
            <Input
              value={config.workspace}
              onChange={update("workspace")}
              placeholder="e.g. D:\\projects\\my-app"
            />
          </div>
        </div>

        <DialogFooter>
          <Button onClick={onSave}>Save</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
