import { useState } from "react";
import { Plus, Trash2, Edit3, Check, X, Globe } from "lucide-react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { cn } from "../lib/utils";
import { useNavigate, useOutletContext } from "react-router-dom";

const PRESET_PROVIDERS = [
  { name: "GitHub Models", free: true, icon: "https://github.githubassets.com/assets/GitHub-Mark-ea2971cee799.png" },
  { name: "Google Gemini", free: true, icon: "https://gw.alicdn.com/imgextra/i2/O1CN01pDWy7z25caEvmJ3u1_!!6000000007547-2-tps-400-400.png" },
  { name: "OpenRouter", free: true, icon: "https://gw.alicdn.com/imgextra/i4/O1CN01oX74jS1ciQR9xBtZ2_!!6000000003634-2-tps-252-252.png" },
  { name: "SiliconFlow", free: true, icon: "https://img.alicdn.com/imgextra/i1/O1CN01TUkzVC1clAoPa2ix8_!!6000000003640-2-tps-520-520.png" },
  { name: "Zhipu", free: true, icon: "https://img.alicdn.com/imgextra/i2/O1CN01TFZcQz23xX7qacIEv_!!6000000007322-2-tps-640-640.png" },
  { name: "Aliyun", free: false, icon: "https://gw.alicdn.com/imgextra/i4/O1CN01aDHDeq1mgj7gbRkhi_!!6000000004984-2-tps-400-400.png" },
  { name: "Anthropic", free: false, icon: "https://gw.alicdn.com/imgextra/i2/O1CN014LwvBJ1tNDYvc3FfA_!!6000000005889-2-tps-400-400.png" },
  { name: "Azure OpenAI", free: false, icon: "https://gw.alicdn.com/imgextra/i2/O1CN01R42n1y1hQAjCEiVlB_!!6000000004271-2-tps-400-400.png" },
  { name: "DeepSeek", free: false, icon: "https://gw.alicdn.com/imgextra/i4/O1CN01YfmXc81ogO3pR0aW8_!!6000000005254-2-tps-400-400.png" },
  { name: "Kimi", free: false, icon: "https://gw.alicdn.com/imgextra/i1/O1CN01xCKAr81Yz8Q9pXh1u_!!6000000003129-2-tps-400-400.png" },
  { name: "MiniMax", free: false, icon: "https://gw.alicdn.com/imgextra/i1/O1CN01B0FaVn1VzBcO4nF1C_!!6000000002723-2-tps-400-400.png" },
  { name: "ModelScope", free: false, icon: "https://gw.alicdn.com/imgextra/i4/O1CN01exenB61EAwhgY4pmA_!!6000000000312-2-tps-400-400.png" },
  { name: "OpenAI", free: false, icon: "https://gw.alicdn.com/imgextra/i3/O1CN01rQSexq1D7S4AYstKh_!!6000000000169-2-tps-400-400.png" },
  { name: "OpenAI (Response API)", free: false, icon: "https://gw.alicdn.com/imgextra/i3/O1CN01rQSexq1D7S4AYstKh_!!6000000000169-2-tps-400-400.png" },
  { name: "Volcano Engine", free: false, icon: "https://img.alicdn.com/imgextra/i1/O1CN01KusRg42AJPkUV5ken_!!6000000008182-2-tps-1892-1660.png" },
  { name: "Xiaomi MiMo Token Plan", free: false, icon: "https://img.alicdn.com/imgextra/i1/O1CN01TSCOAt1XP7fywLDei_!!6000000002915-2-tps-3483-3483.png" },
];

const TABS = [
  { key: "cloud", label: "云端提供商" },
  { key: "local", label: "本地 & 自定义" },
];

export default function ModelsPage() {
  const navigate = useNavigate();
  const { modelConfigs = [], addModelConfig, deleteModelConfig, updateModelConfig } = useOutletContext();
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [activeTab, setActiveTab] = useState("cloud");
  const [form, setForm] = useState({ name: "", apiKey: "", baseUrl: "https://api.openai.com/v1", modelName: "" });

  const openAdd = () => {
    setForm({ name: "", apiKey: "", baseUrl: "https://api.openai.com/v1", modelName: "" });
    setEditingId(null);
    setShowForm(true);
  };
  const openEdit = (m) => {
    setForm({ name: m.name, apiKey: m.apiKey, baseUrl: m.baseUrl, modelName: m.modelName });
    setEditingId(m.id);
    setShowForm(true);
  };
  const handleSave = async () => {
    if (!form.name.trim() || !form.modelName.trim()) return;
    if (editingId) await updateModelConfig(editingId, form);
    else await addModelConfig(form);
    setShowForm(false);
  };

  const configured = modelConfigs || [];

  return (
    <div className="flex flex-col h-full overflow-hidden">
      <div className="flex items-center px-5 h-[72px] border-b border-border flex-shrink-0">
        <div className="flex items-center gap-2 text-sm">
          <button onClick={() => navigate("/chat")} className="text-muted-foreground hover:text-foreground transition-colors">设置</button>
          <span className="text-muted-foreground/40">/</span>
          <span className="text-foreground font-medium">模型</span>
        </div>
      </div>

      <div className="flex-1 overflow-auto">
        <div>
          <div className="flex items-center px-5 h-[71px] gap-4">
            <h2 className="text-sm font-normal">提供商</h2>
            <div className="flex-1" />
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-1.5 h-[34px] px-3 rounded-full border border-border/50 text-sm text-muted-foreground">
                <span>默认LLM:</span>
                <span className="text-foreground/50">— / —</span>
                <button className="text-xs text-primary hover:underline ml-1">编辑</button>
              </div>
              <Button size="sm" onClick={openAdd} className="gap-1.5 h-8 rounded-md bg-orange-500 hover:bg-orange-600 text-white text-sm font-medium">
                <Plus className="w-3.5 h-3.5" />
                添加提供商
              </Button>
            </div>
          </div>

          <div className="flex border-b border-border/50 mb-4">
            {TABS.map((tab) => (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={cn(
                  "px-5 h-[42px] text-[13px] font-medium transition-colors border-b-2 -mb-[2px]",
                  activeTab === tab.key
                    ? "text-primary border-primary"
                    : "text-muted-foreground/60 border-transparent hover:text-muted-foreground"
                )}
              >
                {tab.label} ({tab.key === "cloud" ? configured.length + PRESET_PROVIDERS.length : 0})
              </button>
            ))}
          </div>

          {showForm && (
            <div className="mx-5 mb-4 rounded-xl border border-dashed border-border/50 bg-muted/20 p-5">
              <div className="flex items-center justify-between mb-3">
                <span className="text-sm font-medium">{editingId ? "编辑提供商" : "添加提供商"}</span>
                <button onClick={() => setShowForm(false)} className="text-muted-foreground hover:text-foreground"><X className="w-4 h-4" /></button>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="flex flex-col gap-1"><label className="text-xs font-medium text-muted-foreground">名称</label><Input value={form.name} onChange={(e) => setForm(p => ({ ...p, name: e.target.value }))} placeholder="如: DeepSeek" /></div>
                <div className="flex flex-col gap-1"><label className="text-xs font-medium text-muted-foreground">Model</label><Input value={form.modelName} onChange={(e) => setForm(p => ({ ...p, modelName: e.target.value }))} placeholder="如: deepseek-v4" /></div>
                <div className="flex flex-col gap-1 col-span-2"><label className="text-xs font-medium text-muted-foreground">API URL</label><Input value={form.baseUrl} onChange={(e) => setForm(p => ({ ...p, baseUrl: e.target.value }))} placeholder="https://api.openai.com/v1" /></div>
                <div className="flex flex-col gap-1 col-span-2"><label className="text-xs font-medium text-muted-foreground">API Key</label><Input type="password" value={form.apiKey} onChange={(e) => setForm(p => ({ ...p, apiKey: e.target.value }))} placeholder="sk-xxxxxxxx" /></div>
              </div>
              <div className="flex justify-end gap-2 mt-3">
                <Button variant="ghost" size="sm" onClick={() => setShowForm(false)}>取消</Button>
                <Button size="sm" onClick={handleSave} disabled={!form.name.trim() || !form.modelName.trim()}><Check className="w-3.5 h-3.5 mr-1" />保存</Button>
              </div>
            </div>
          )}

          <div className="mx-5 mb-4 rounded-xl bg-muted/30 border border-border/50 p-[18px]">
            <div className="flex items-center gap-2 mb-3 text-sm text-muted-foreground">
              已配置 {configured.length} 个提供商
            </div>
            {configured.length === 0 ? (
              <p className="text-xs text-muted-foreground/50 text-center py-8">暂无已配置的提供商</p>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                {configured.map((m) => (
                  <div key={m.id} className="rounded-[14px] bg-card border border-border/30 p-[22px] hover:border-border/60 transition-colors">
                    <div className="flex items-start justify-between mb-2">
                      <div className="flex items-center gap-2.5">
                        <div className="w-9 h-9 rounded-lg bg-emerald-500/10 flex items-center justify-center"><Globe className="w-[18px] h-[18px] text-emerald-400" /></div>
                        <div><h3 className="text-sm font-medium leading-tight">{m.name}</h3><p className="text-[11px] text-muted-foreground">{m.modelName}</p></div>
                      </div>
                      <div className="flex gap-0.5">
                        <button onClick={() => openEdit(m)} className="w-7 h-7 flex items-center justify-center rounded-md hover:bg-accent text-muted-foreground hover:text-foreground transition-colors"><Edit3 className="w-3.5 h-3.5" /></button>
                        <button onClick={() => deleteModelConfig(m.id)} className="w-7 h-7 flex items-center justify-center rounded-md hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-colors"><Trash2 className="w-3.5 h-3.5" /></button>
                      </div>
                    </div>
                    <div className="text-[11px] text-muted-foreground/50 truncate mt-2 pt-2 border-t border-border/20">{m.baseUrl}</div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="mx-5 mb-4 rounded-xl border border-dashed border-border/50 bg-card p-[18px]">
            <div className="flex items-center gap-2 mb-3 text-sm text-muted-foreground">可用提供商</div>
            <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-2">
              {PRESET_PROVIDERS.map((p, i) => (
                <div key={i} className="flex items-center gap-[10px] rounded-lg bg-muted/40 border border-border/40 px-[14px] py-[10px] hover:border-border/60 transition-colors cursor-pointer">
                  <img src={p.icon} alt={p.name} width="24" height="24" className="rounded-[6px] object-cover flex-shrink-0" />
                  <span className="text-sm flex-1 truncate">{p.name}</span>
                  {p.free && (
                    <span className="text-[10px] font-bold text-white px-[6px] py-[2px] rounded leading-[15.7px]" style={{ backgroundImage: "linear-gradient(135deg, rgb(16, 185, 129), rgb(5, 150, 105))" }}>FREE</span>
                  )}
                  <span className="text-[11px] text-indigo-500 dark:text-indigo-400 flex-shrink-0">配置 →</span>
                </div>
              ))}
            </div>
          </div>

        </div>
      </div>
    </div>
  );
}
