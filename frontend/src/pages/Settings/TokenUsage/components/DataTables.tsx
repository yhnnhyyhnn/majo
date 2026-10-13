import { Card, Table } from "@agentscope-ai/design";
import { useTranslation } from "react-i18next";
import { formatCompact } from "../../../../utils/formatNumber";
import { formatCostUsd } from "./SummaryCards";
import styles from "../index.module.less";

interface ByModelData {
  key: string;
  model: string;
  prompt_tokens: number;
  completion_tokens: number;
  call_count: number;
  /** null = no priced record for this model (ADR-0017). */
  cost_usd?: number | null;
}

interface ByDateData {
  key: string;
  date: string;
  prompt_tokens: number;
  completion_tokens: number;
  call_count: number;
}

interface DataTablesProps {
  byModelData: ByModelData[];
  byDateData: ByDateData[];
}

export function DataTables({ byModelData, byDateData }: DataTablesProps) {
  const { t } = useTranslation();

  const byModelColumns = [
    {
      title: t("tokenUsage.model"),
      dataIndex: "model",
      key: "model",
    },
    {
      title: t("tokenUsage.promptTokens"),
      dataIndex: "prompt_tokens",
      key: "prompt_tokens",
      render: (v: number) => formatCompact(v),
      sorter: (a: ByModelData, b: ByModelData) =>
        a.prompt_tokens - b.prompt_tokens,
    },
    {
      title: t("tokenUsage.completionTokens"),
      dataIndex: "completion_tokens",
      key: "completion_tokens",
      render: (v: number) => formatCompact(v),
      sorter: (a: ByModelData, b: ByModelData) =>
        a.completion_tokens - b.completion_tokens,
    },
    {
      title: t("tokenUsage.totalTokens"),
      key: "total_tokens",
      render: (_: unknown, record: ByModelData) =>
        formatCompact(record.prompt_tokens + record.completion_tokens),
      sorter: (a: ByModelData, b: ByModelData) =>
        a.prompt_tokens +
        a.completion_tokens -
        (b.prompt_tokens + b.completion_tokens),
    },
    {
      title: t("tokenUsage.totalCalls"),
      dataIndex: "call_count",
      key: "call_count",
      render: (v: number) => formatCompact(v),
      sorter: (a: ByModelData, b: ByModelData) => a.call_count - b.call_count,
    },
    {
      title: t("tokenUsage.cost"),
      dataIndex: "cost_usd",
      key: "cost_usd",
      render: (v: number | null) => formatCostUsd(v),
      sorter: (a: ByModelData, b: ByModelData) =>
        (a.cost_usd ?? -1) - (b.cost_usd ?? -1),
    },
  ];

  const byDateColumns = [
    {
      title: t("tokenUsage.date"),
      dataIndex: "date",
      key: "date",
    },
    {
      title: t("tokenUsage.promptTokens"),
      dataIndex: "prompt_tokens",
      key: "prompt_tokens",
      render: (v: number) => formatCompact(v),
      sorter: (a: ByDateData, b: ByDateData) =>
        a.prompt_tokens - b.prompt_tokens,
    },
    {
      title: t("tokenUsage.completionTokens"),
      dataIndex: "completion_tokens",
      key: "completion_tokens",
      render: (v: number) => formatCompact(v),
      sorter: (a: ByDateData, b: ByDateData) =>
        a.completion_tokens - b.completion_tokens,
    },
    {
      title: t("tokenUsage.totalTokens"),
      key: "total_tokens",
      render: (_: unknown, record: ByDateData) =>
        formatCompact(record.prompt_tokens + record.completion_tokens),
      sorter: (a: ByDateData, b: ByDateData) =>
        a.prompt_tokens +
        a.completion_tokens -
        (b.prompt_tokens + b.completion_tokens),
    },
    {
      title: t("tokenUsage.totalCalls"),
      dataIndex: "call_count",
      key: "call_count",
      render: (v: number) => formatCompact(v),
      sorter: (a: ByDateData, b: ByDateData) => a.call_count - b.call_count,
    },
  ];

  return (
    <>
      {byModelData.length > 0 && (
        <Card
          className={`${styles.tableCard} mobile-scroll-x`}
          title={t("tokenUsage.byModel")}
        >
          <Table
            columns={byModelColumns}
            dataSource={byModelData}
            pagination={{ pageSize: 10 }}
            size="small"
            scroll={{ x: "max-content" }}
          />
        </Card>
      )}

      {byDateData.length > 0 && (
        <Card
          className={`${styles.tableCard} mobile-scroll-x`}
          title={t("tokenUsage.byDate")}
        >
          <Table
            columns={byDateColumns}
            dataSource={byDateData}
            pagination={{ pageSize: 10 }}
            size="small"
            scroll={{ x: "max-content" }}
          />
        </Card>
      )}
    </>
  );
}
