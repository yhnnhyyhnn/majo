import { useEffect, useState } from "react";
import { Modal, Descriptions, Tag, Button, message, Spin, Space, Input } from "antd";
import { RefreshCw, Save, Settings } from "lucide-react";
import { useTranslation } from "react-i18next";
import { getApiUrl } from "@/api/config";
import { buildAuthHeaders } from "@/api/authHeaders";

interface SyncConfig {
  key: string;
  url: string;
  last_synced_at: string | null;
  synced_count: number;
  status: string;
}

const KEY_LABELS: Record<string, string> = {
  catalog: "pluginManager.officialCatalog",
  market: "pluginManager.market",
};

export function SyncSettingsModal({
  open,
  onClose,
  onSynced,
}: {
  open: boolean;
  onClose: () => void;
  onSynced: () => void;
}) {
  const { t } = useTranslation();
  const [configs, setConfigs] = useState<SyncConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState<string | null>(null);
  const [saving, setSaving] = useState<string | null>(null);
  const [editUrls, setEditUrls] = useState<Record<string, string>>({});

  const fetchConfigs = async () => {
    setLoading(true);
    try {
      const res = await fetch(getApiUrl("/plugins/sync-config"), {
        headers: buildAuthHeaders(),
      });
      if (res.ok) {
        const data = await res.json();
        setConfigs(data);
        const urls: Record<string, string> = {};
        data.forEach((c: SyncConfig) => { urls[c.key] = c.url; });
        setEditUrls(urls);
      }
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) fetchConfigs();
  }, [open]);

  const handleSync = async (key: string) => {
    setSyncing(key);
    try {
      const res = await fetch(getApiUrl(`/plugins/${key}/sync`), {
        method: "POST",
        headers: buildAuthHeaders(),
      });
      if (res.ok) {
        message.success(t("pluginManager.syncSuccess"));
        fetchConfigs();
        onSynced();
      } else {
        const body = await res.json().catch(() => ({}));
        message.error(body.detail ?? t("pluginManager.syncFailed"));
      }
    } catch {
      message.error(t("pluginManager.syncFailed"));
    } finally {
      setSyncing(null);
    }
  };

  const handleSaveUrl = async (key: string) => {
    const url = editUrls[key];
    if (!url?.trim()) return;
    setSaving(key);
    try {
      const res = await fetch(getApiUrl("/plugins/sync-config"), {
        method: "PUT",
        headers: { ...buildAuthHeaders(), "Content-Type": "application/json" },
        body: JSON.stringify({ key, url }),
      });
      if (res.ok) {
        message.success(t("pluginManager.syncSaved"));
        fetchConfigs();
        setEditUrls((prev) => ({ ...prev, [key]: url }));
      } else {
        message.error(t("pluginManager.syncSavedFailed"));
      }
    } catch {
      message.error(t("pluginManager.syncSavedFailed"));
    } finally {
      setSaving(null);
    }
  };

  const isPending = (c: SyncConfig) =>
    !c.last_synced_at && c.status === "pending";

  const statusTag = (status: string) => {
    if (status === "success")
      return <Tag color="green">{t("pluginManager.syncSuccess")}</Tag>;
    if (status === "failed")
      return <Tag color="red">{t("pluginManager.syncFailed")}</Tag>;
    return <Tag>{t("pluginManager.syncPending")}</Tag>;
  };

  return (
    <Modal
      title={
        <Space>
          <Settings size={18} />
          {t("pluginManager.syncSettings")}
        </Space>
      }
      open={open}
      onCancel={onClose}
      footer={null}
      width={640}
    >
      <Spin spinning={loading}>
        {configs.map((c) => (
          <Descriptions
            key={c.key}
            title={
              <Space>
                {t(KEY_LABELS[c.key] ?? c.key)}
                {statusTag(c.status)}
              </Space>
            }
            column={1}
            bordered
            size="small"
            style={{ marginBottom: 16 }}
            extra={
              <Button
                size="small"
                icon={<RefreshCw size={14} />}
                loading={syncing === c.key}
                onClick={() => handleSync(c.key)}
              >
                {t("pluginManager.syncNow")}
              </Button>
            }
          >
            <Descriptions.Item label={t("pluginManager.syncUrl")}>
              <Space style={{ width: "100%" }}>
                <Input
                  size="small"
                  value={editUrls[c.key] ?? c.url}
                  onChange={(e) =>
                    setEditUrls((prev) => ({ ...prev, [c.key]: e.target.value }))
                  }
                  style={{ flex: 1 }}
                />
                <Button
                  size="small"
                  icon={<Save size={14} />}
                  loading={saving === c.key}
                  onClick={() => handleSaveUrl(c.key)}
                >
                  {t("pluginManager.syncSave")}
                </Button>
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label={t("pluginManager.lastSync")}>
              {c.last_synced_at ?? "-"}
            </Descriptions.Item>
            <Descriptions.Item label={t("pluginManager.syncCount")}>
              {c.synced_count}
            </Descriptions.Item>
          </Descriptions>
        ))}
        {!loading && configs.length === 0 && (
          <div style={{ textAlign: "center", padding: 24, color: "#999" }}>
            {t("pluginManager.noSyncConfig")}
          </div>
        )}
      </Spin>
    </Modal>
  );
}
