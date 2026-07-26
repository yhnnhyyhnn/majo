import { useState } from "react";
import { Plus, Trash2, Edit3, Check, X, Globe, ExternalLink } from "lucide-react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Card } from "./ui/card";
import { ScrollArea } from "./ui/scroll-area";
import { cn } from "../lib/utils";
import { useTranslation } from "react-i18next";

// ── Preset providers ──────────────────────────
const PRESET_PROVIDERS = [
  { name: "GitHub Models", desc: "FREE", url: "https://models.github.com" },
  { name: "Google Gemini", desc: "FREE", url: "https://ai.google.dev" },
  { name: "OpenRouter", desc: "FREE", url: "https://openrouter.ai" },
  { name: "Groq", desc: "FREE", url: "https://groq.com" },
  { name: "Together AI", desc: "FREE", url: "https://together.ai" },
];

export default function ModelsPage({
  modelConfigs,
  onAdd,
  onDelete,
  onUpdate,
  onBack,
}) {
  const { t } = useTranslation();
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [form, setForm] = useState({ name: "", apiKey: "", baseUrl: "https://api.openai.com/v1", modelName: "" });

  const openAdd = () => {
    setForm({ name: "", apiKey: "", baseUrl: "https://api.openai.com/v1", modelName: "" });
    setEditingId(null);
    setShowForm(true);
  };

  const openEdit = (model) => {
    setForm({ name: model.name, apiKey: model.apiKey, baseUrl: model.baseUrl, modelName: model.modelName });
    setEditingId(model.id);
    setShowForm(true);
  };

  const handleSave = async () => {
    if (!form.name.trim() || !form.modelName.trim()) return;
    if (editingId) {
      await onUpdate(editingId, form);
    } else {
      await onAdd(form);
    }
    setShowForm(false);
  };

  const configured = modelConfigs || [];

  return (
    <div className="flex flex-col h-full">
      {/* Page header with breadcrumb */}
      <div className="flex items-center justify-between px-5 py-5 border-b border-border/50">
        <div className="flex items-center gap-2 text-sm">
          <button onClick={onBack} className="text-muted-foreground hover:text-foreground transition-colors">
            设置
          </button>
          <span className="text-muted-foreground/40">/</span>
          <span className="text-foreground font-medium">模型</span>
        </div>
      </div>

      <ScrollArea className="flex-1">
        <div className="p-5 space-y-5 max-w-4xl">
          {/* Provider section header */}
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">提供商</h2>
            <Button size="sm" onClick={openAdd} className="gap-1.5">
              <Plus className="w-3.5 h-3.5" />
              添加提供商
            </Button>
          </div>

          {/* Add/Edit form */}
          {showForm && (
            <Card className="p-4 space-y-3 border-dashed">
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">
                  {editingId ? "编辑提供商" : "添加提供商"}
                </span>
                <button onClick={() => setShowForm(false)} className="text-muted-foreground hover:text-foreground">
                  <X className="w-4 h-4" />
                </button>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-1">
                  <label className="text-xs font-medium text-muted-foreground">名称</label>
                  <Input value={form.name} onChange={(e) => setForm(p => ({ ...p, name: e.target.value }))} placeholder="如: DeepSeek" />
                </div>
                <div className="flex flex-col gap-1">
                  <label className="text-xs font-medium text-muted-foreground">Model</label>
                  <Input value={form.modelName} onChange={(e) => setForm(p => ({ ...p, modelName: e.target.value }))} placeholder="如: deepseek-v4" />
                </div>
                <div className="flex flex-col gap-1 col-span-2">
                  <label className="text-xs font-medium text-muted-foreground">API URL</label>
                  <Input value={form.baseUrl} onChange={(e) => setForm(p => ({ ...p, baseUrl: e.target.value }))} placeholder="https://api.openai.com/v1" />
                </div>
                <div className="flex flex-col gap-1 col-span-2">
                  <label className="text-xs font-medium text-muted-foreground">API Key</label>
                  <Input type="password" value={form.apiKey} onChange={(e) => setForm(p => ({ ...p, apiKey: e.target.value }))} placeholder="sk-xxxxxxxx" />
                </div>
              </div>
              <div className="flex justify-end gap-2 pt-1">
                <Button variant="ghost" size="sm" onClick={() => setShowForm(false)}>取消</Button>
                <Button size="sm" onClick={handleSave} disabled={!form.name.trim() || !form.modelName.trim()}>
                  <Check className="w-3.5 h-3.5 mr-1" />
                  保存
                </Button>
              </div>
            </Card>
          )}

          {/* Configured providers panel */}
          <div className="rounded-xl bg-muted/30 border border-border/50 p-5">
            <div className="flex items-center justify-between mb-3">
              <span className="text-sm font-medium text-muted-foreground">
                已配置 {configured.length} 个提供商
              </span>
            </div>
            {configured.length === 0 ? (
              <p className="text-xs text-muted-foreground/50 text-center py-8">
                暂无已配置的提供商，点击"添加提供商"创建
              </p>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {configured.map((m) => (
                  <div
                    key={m.id}
                    className="rounded-xl bg-white/85 dark:bg-white/[0.04] border border-border/40 p-5 hover:border-border transition-colors"
                  >
                    <div className="flex items-start justify-between mb-2">
                      <div className="flex items-center gap-2">
                        <div className="w-8 h-8 rounded-lg bg-emerald-500/10 flex items-center justify-center">
                          <Globe className="w-4 h-4 text-emerald-400" />
                        </div>
                        <div>
                          <h3 className="text-sm font-medium">{m.name}</h3>
                          <p className="text-[11px] text-muted-foreground">{m.modelName}</p>
                        </div>
                      </div>
                      <div className="flex gap-1">
                        <button
                          onClick={() => openEdit(m)}
                          className="w-7 h-7 flex items-center justify-center rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"
                        >
                          <Edit3 className="w-3.5 h-3.5" />
                        </button>
                        <button
                          onClick={() => onDelete(m.id)}
                          className="w-7 h-7 flex items-center justify-center rounded-md hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </div>
                    <div className="text-[11px] text-muted-foreground/60 truncate mt-2 pt-2 border-t border-border/30">
                      {m.baseUrl}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Available providers panel */}
          <div className="rounded-xl border border-dashed border-border/50 p-5">
            <span className="text-sm font-medium text-muted-foreground">
              可用提供商
            </span>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mt-3">
              {PRESET_PROVIDERS.map((p, i) => (
                <div
                  key={i}
                  className="flex items-center justify-between rounded-xl bg-card border border-border/40 p-4 hover:border-border transition-colors"
                >
                  <div>
                    <h3 className="text-sm font-medium">{p.name}</h3>
                    <p className="text-[11px] text-emerald-500/80 font-medium">{p.desc}</p>
                  </div>
                  <a
                    href={p.url}
                    target="_blank"
                    rel="noreferrer"
                    className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                  >
                    配置
                    <ExternalLink className="w-3 h-3" />
                  </a>
                </div>
              ))}
            </div>
          </div>
        </div>
      </ScrollArea>
    </div>
  );
}
