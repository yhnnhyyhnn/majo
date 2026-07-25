import { useState } from "react";
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
import { Card } from "./ui/card";
import { Plus, Trash2, Check, ChevronDown, ChevronUp } from "lucide-react";
import { useTranslation } from "react-i18next";

const FIELD_CLASS = "flex flex-col gap-1.5";

export default function SettingsDialog({
  open,
  onOpenChange,
  config,
  onConfigChange,
  onSave,
  modelConfigs,
  onAddModel,
  onDeleteModel,
}) {
  const [showAddModel, setShowAddModel] = useState(false);
  const [newModel, setNewModel] = useState({
    name: "",
    apiKey: "",
    baseUrl: "https://api.openai.com/v1",
    modelName: "",
  });
  const { t } = useTranslation();

  const update = (key) => (e) =>
    onConfigChange({ [key]: e.target.value });

  const updateNew = (key) => (e) =>
    setNewModel((prev) => ({ ...prev, [key]: e.target.value }));

  const handleAddModel = async () => {
    if (!newModel.name.trim() || !newModel.modelName.trim()) return;
    await onAddModel(newModel);
    setNewModel({ name: "", apiKey: "", baseUrl: "https://api.openai.com/v1", modelName: "" });
    setShowAddModel(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("settings.title")}</DialogTitle>
          <DialogDescription>
            {t("settings.description")}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-6 py-2">
          {/* Default model settings (fallback) */}
          <div>
            <h3 className="text-sm font-semibold mb-3 text-muted-foreground">{t("settings.defaultSection")}</h3>
            <div className="space-y-3">
              <div className={FIELD_CLASS}>
                <label className="text-sm font-medium">{t("settings.apiUrl")}</label>
                <Input
                  value={config.baseUrl}
                  onChange={update("baseUrl")}
                  placeholder="https://api.openai.com/v1"
                />
              </div>
              <div className={FIELD_CLASS}>
                <label className="text-sm font-medium">{t("settings.apiKey")}</label>
                <Input
                  type="password"
                  value={config.apiKey}
                  onChange={update("apiKey")}
                  placeholder="sk-xxxxxxxx"
                />
              </div>
              <div className={FIELD_CLASS}>
                <label className="text-sm font-medium">{t("settings.model")}</label>
                <Input
                  value={config.modelName}
                  onChange={update("modelName")}
                  placeholder="gpt-4o-mini"
                />
              </div>
            </div>
          </div>

          {/* Divider */}
          <div className="border-t" />

          {/* Model configs */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-sm font-semibold text-muted-foreground">
                {t("settings.modelConfigs")} ({modelConfigs.length})
              </h3>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowAddModel(!showAddModel)}
              >
                {showAddModel ? (
                  <ChevronUp className="w-3.5 h-3.5 mr-1" />
                ) : (
                  <Plus className="w-3.5 h-3.5 mr-1" />
                )}
                {showAddModel ? t("settings.collapse") : t("settings.add")}
              </Button>
            </div>

            {/* Add model form */}
            {showAddModel && (
              <Card className="p-4 mb-3 space-y-3 border-dashed">
                <div className={FIELD_CLASS}>
                  <label className="text-sm font-medium">{t("settings.name")}</label>
                  <Input
                    value={newModel.name}
                    onChange={updateNew("name")}
                    placeholder={t("settings.namePlaceholder")}
                  />
                </div>
                <div className={FIELD_CLASS}>
                  <label className="text-sm font-medium">{t("settings.apiUrl")}</label>
                  <Input
                    value={newModel.baseUrl}
                    onChange={updateNew("baseUrl")}
                    placeholder="https://api.openai.com/v1"
                  />
                </div>
                <div className={FIELD_CLASS}>
                  <label className="text-sm font-medium">{t("settings.apiKey")}</label>
                  <Input
                    type="password"
                    value={newModel.apiKey}
                    onChange={updateNew("apiKey")}
                    placeholder="sk-xxxxxxxx"
                  />
                </div>
                <div className={FIELD_CLASS}>
                  <label className="text-sm font-medium">{t("settings.model")}</label>
                  <Input
                    value={newModel.modelName}
                    onChange={updateNew("modelName")}
                    placeholder={t("settings.modelPlaceholder")}
                  />
                </div>
                <Button
                  size="sm"
                  className="w-full"
                  onClick={handleAddModel}
                  disabled={!newModel.name.trim() || !newModel.modelName.trim()}
                >
                  <Check className="w-3.5 h-3.5 mr-1" />
                  {t("settings.confirm")}
                </Button>
              </Card>
            )}

            {/* Model list */}
            {modelConfigs.length === 0 && !showAddModel && (
              <p className="text-xs text-muted-foreground text-center py-4">
                {t("settings.noModels")}
              </p>
            )}
            <div className="space-y-2">
              {modelConfigs.map((m) => (
                <Card
                  key={m.id}
                  className="p-3 flex items-center justify-between gap-3"
                >
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium truncate">{m.name}</div>
                    <div className="text-xs text-muted-foreground truncate">
                      {m.modelName} · {m.baseUrl}
                    </div>
                  </div>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7 text-muted-foreground hover:text-destructive flex-shrink-0"
                    onClick={() => onDeleteModel(m.id)}
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </Button>
                </Card>
              ))}
            </div>
          </div>

          {/* Divider */}
          <div className="border-t" />

          {/* Workspace */}
          <div className={FIELD_CLASS}>
            <label className="text-sm font-medium">{t("settings.workspace")}</label>
            <Input
              value={config.workspace}
              onChange={update("workspace")}
              placeholder={t("settings.workspacePlaceholder")}
            />
          </div>
        </div>

        <DialogFooter>
          <Button onClick={onSave}>{t("settings.save")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
