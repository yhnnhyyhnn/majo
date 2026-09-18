import { useCallback, useEffect, useState } from "react";
import { Card, Button, InputNumber, Switch } from "@agentscope-ai/design";
import { useTranslation } from "react-i18next";
import { securityApi } from "../../../../api/modules/security";
import { useAppMessage } from "../../../../hooks/useAppMessage";
import styles from "../index.module.less";

/**
 * Doom-loop detection settings (security.doom_loop): enable/disable and the
 * warn/stop thresholds for repeated identical tool calls.
 */
export function DoomLoopTab() {
  const { t } = useTranslation();
  const { message } = useAppMessage();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [enabled, setEnabled] = useState(true);
  const [warnAfter, setWarnAfter] = useState<number>(3);
  const [stopAfter, setStopAfter] = useState<number>(6);

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      const data = await securityApi.getDoomLoop();
      setEnabled(data?.enabled ?? true);
      setWarnAfter(data?.warn_after ?? 3);
      setStopAfter(data?.stop_after ?? 6);
    } catch {
      message.error(t("security.doomLoop.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [t, message]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleSave = useCallback(async () => {
    if (stopAfter <= warnAfter) {
      message.error(t("security.doomLoop.stopMustExceedWarn"));
      return;
    }
    setSaving(true);
    try {
      await securityApi.updateDoomLoop({
        enabled,
        warn_after: warnAfter,
        stop_after: stopAfter,
      });
      message.success(t("security.doomLoop.saved"));
    } catch {
      message.error(t("security.doomLoop.saveFailed"));
    } finally {
      setSaving(false);
    }
  }, [enabled, warnAfter, stopAfter, t, message]);

  return (
    <div className={styles.tabContent}>
      <Card loading={loading} className={styles.doomLoopCard}>
        <div className={styles.doomLoopRow}>
          <span className={styles.doomLoopLabel}>
            {t("security.doomLoop.enabled")}
          </span>
          <Switch checked={enabled} onChange={setEnabled} />
        </div>
        <p className={styles.doomLoopHint}>
          {t("security.doomLoop.description")}
        </p>

        <div className={styles.doomLoopRow}>
          <span className={styles.doomLoopLabel}>
            {t("security.doomLoop.warnAfter")}
          </span>
          <InputNumber
            min={2}
            max={20}
            value={warnAfter}
            onChange={(v) => setWarnAfter(typeof v === "number" ? v : 3)}
            disabled={!enabled}
          />
        </div>
        <div className={styles.doomLoopRow}>
          <span className={styles.doomLoopLabel}>
            {t("security.doomLoop.stopAfter")}
          </span>
          <InputNumber
            min={3}
            max={50}
            value={stopAfter}
            onChange={(v) => setStopAfter(typeof v === "number" ? v : 6)}
            disabled={!enabled}
          />
        </div>

        <Button type="primary" onClick={handleSave} loading={saving}>
          {t("common.save")}
        </Button>
      </Card>
    </div>
  );
}
