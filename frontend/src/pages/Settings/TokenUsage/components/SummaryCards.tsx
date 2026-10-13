import { Card } from "@agentscope-ai/design";
import { useTranslation } from "react-i18next";
import { formatCompact } from "../../../../utils/formatNumber";
import styles from "../index.module.less";

interface SummaryCardsProps {
  totalCalls: number;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalTokens: number;
  /** null = no priced model in scope; shown as a dash (ADR-0017). */
  totalCostUsd?: number | null;
}

/** USD display: keep sub-cent precision, avoid long tails for big sums. */
export function formatCostUsd(cost: number | null | undefined): string {
  if (cost == null) return "—";
  if (cost === 0) return "$0.00";
  if (cost < 0.01) return `$${cost.toFixed(4)}`;
  if (cost < 1000) return `$${cost.toFixed(2)}`;
  return `$${formatCompact(cost)}`;
}

export function SummaryCards({
  totalCalls,
  totalPromptTokens,
  totalCompletionTokens,
  totalTokens,
  totalCostUsd = null,
}: SummaryCardsProps) {
  const { t } = useTranslation();

  return (
    <div className={styles.summaryCards}>
      <Card className={styles.card}>
        <div className={styles.cardValue}>{formatCompact(totalCalls)}</div>
        <div className={styles.cardLabel}>{t("tokenUsage.totalCalls")}</div>
      </Card>
      <Card className={styles.card}>
        <div className={styles.cardValue}>
          {formatCompact(totalPromptTokens)}
        </div>
        <div className={styles.cardLabel}>{t("tokenUsage.promptTokens")}</div>
      </Card>
      <Card className={styles.card}>
        <div className={styles.cardValue}>
          {formatCompact(totalCompletionTokens)}
        </div>
        <div className={styles.cardLabel}>
          {t("tokenUsage.completionTokens")}
        </div>
      </Card>
      <Card className={styles.card}>
        <div className={styles.cardValue}>{formatCompact(totalTokens)}</div>
        <div className={styles.cardLabel}>{t("tokenUsage.totalTokens")}</div>
      </Card>
      <Card className={styles.card}>
        <div className={styles.cardValue}>{formatCostUsd(totalCostUsd)}</div>
        <div className={styles.cardLabel}>{t("tokenUsage.totalCost")}</div>
      </Card>
    </div>
  );
}
