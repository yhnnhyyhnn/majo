import {
  CaretDownOutlined,
  CaretRightOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  ReloadOutlined,
  UploadOutlined,
} from "@ant-design/icons";
import { Modal, Switch } from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { projectDirectoryApi } from "../../api/modules/projectDirectory";
import {
  UploadConflictError,
  workspaceApi,
} from "../../api/modules/workspace";
import { useAppMessage } from "../../hooks/useAppMessage";
import { buildMemoryTree, type MemoryTreeNode } from "../Agent/Workspace/components/FileItem";
import type {
  DailyMemoryFile,
  MarkdownFile,
} from "../../api/types";
import FileTree from "../Coding/FileTree";
import styles from "./index.module.less";

export type FilesSource = "workspace" | "profile" | "daily" | "digest";

interface FilesNavigatorProps {
  source: FilesSource;
  onSourceChange: (source: FilesSource) => void;
  files: MarkdownFile[];
  activeTabPath: string;
  dailyMemories: DailyMemoryFile[];
  enabledFiles: string[];
  onFileClick: (file: MarkdownFile) => void;
  onDailyMemoryClick: (daily: DailyMemoryFile) => void;
  onToggleFileEnabled: (filename: string) => void;
  onWorkspaceFileSelect: (path: string, content: string) => void;
  onRefresh: () => void;
  fileTreeKey: number;
}

function MemoryRow({
  file,
  label,
  level,
  selected,
  onClick,
}: {
  file: DailyMemoryFile;
  label: string;
  level: number;
  selected: boolean;
  onClick: () => void;
}) {
  const size = formatSize(file.size);
  return (
    <div
      className={`${styles.memoryRow} ${
        selected ? styles.memoryRowSelected : ""
      }`}
      style={{ paddingLeft: 8 + level * 16 }}
      onClick={onClick}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === "Enter" && onClick()}
    >
      <span className={styles.memoryRowName}>{label}</span>
      <span className={styles.memoryRowMeta}>{size}</span>
    </div>
  );
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

function DigestNode({
  node,
  level,
  selectedPath,
  onSelect,
}: {
  node: MemoryTreeNode;
  level: number;
  selectedPath: string | null;
  onSelect: (path: string) => void;
}) {
  const [expanded, setExpanded] = useState(level === 0);
  if (node.file) {
    return (
      <MemoryRow
        file={node.file}
        label={node.name}
        level={level}
        selected={selectedPath === `memory/${node.file.filename}`}
        onClick={() => onSelect(node.file!.filename)}
      />
    );
  }
  return (
    <div>
      <div
        className={styles.memoryGroupHeader}
        style={{ paddingLeft: 8 + level * 16 }}
        onClick={() => setExpanded((v) => !v)}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => e.key === "Enter" && setExpanded((v) => !v)}
      >
        {expanded ? <CaretDownOutlined /> : <CaretRightOutlined />}
        {expanded ? <FolderOpenOutlined /> : <FolderOutlined />}
        <span>{node.name}</span>
      </div>
      {expanded && (
        <div className={styles.memoryChildren}>
          {node.children.map((child) => (
            <DigestNode
              key={child.name}
              node={child}
              level={level + 1}
              selectedPath={selectedPath}
              onSelect={onSelect}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export default function FilesNavigator({
  source,
  onSourceChange,
  files,
  activeTabPath,
  dailyMemories,
  enabledFiles,
  onFileClick,
  onDailyMemoryClick,
  onToggleFileEnabled,
  onWorkspaceFileSelect,
  onRefresh,
  fileTreeKey,
}: FilesNavigatorProps) {
  const { t } = useTranslation();
  const { message } = useAppMessage();
  const memoryTree = useMemo(
    () => buildMemoryTree(dailyMemories),
    [dailyMemories],
  );
  const [workspacePath, setWorkspacePath] = useState("");
  const [uploading, setUploading] = useState(false);
  const [pendingUploads, setPendingUploads] = useState<File[] | null>(null);
  const [conflictingNames, setConflictingNames] = useState<string[]>([]);
  const uploadRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    projectDirectoryApi
      .get()
      .then((info) => {
        if (cancelled) return;
        setWorkspacePath(info.workspace_dir ?? info.path);
      })
      .catch(() => {
        if (!cancelled) setWorkspacePath("");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const runUpload = useCallback(
    async (filesToUpload: File[], conflict?: "overwrite" | "skip" | "rename") => {
      setUploading(true);
      try {
        await workspaceApi.uploadFiles(filesToUpload, "", conflict);
        setPendingUploads(null);
        setConflictingNames([]);
        message.success(t("workspace.uploadSuccess"));
        onRefresh();
      } catch (error) {
        if (error instanceof UploadConflictError) {
          setPendingUploads(filesToUpload);
          setConflictingNames(error.files);
          return;
        }
        message.error(
          t("workspace.uploadFailed") + ": " + (error as Error).message,
        );
      } finally {
        setUploading(false);
      }
    },
    [message, onRefresh, t],
  );

  const renderWorkspace = () => (
    <FileTree key={fileTreeKey} onFileSelect={onWorkspaceFileSelect} />
  );

  const renderProfile = () => (
    <div>
      {files.length === 0 ? (
        <div className={styles.navigatorEmpty}>{t("workspace.noFiles")}</div>
      ) : (
        files.map((file) => {
          const enabled = enabledFiles.includes(file.filename);
          const selected = activeTabPath === file.filename;
          return (
            <div
              key={file.filename}
              className={`${styles.profileRow} ${
                selected ? styles.memoryRowSelected : ""
              }`}
              onClick={() => onFileClick(file)}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => e.key === "Enter" && onFileClick(file)}
            >
              <span className={styles.memoryRowName}>{file.filename}</span>
              <Switch
                size="small"
                checked={enabled}
                aria-label={t("workspace.systemPromptToggleTooltip")}
                onClick={(_checked, event) => {
                  event.stopPropagation();
                  onToggleFileEnabled(file.filename);
                }}
              />
            </div>
          );
        })
      )}
    </div>
  );

  const renderDaily = () => {
    const selectedPath = activeTabPath;
    return (
      <div>
        {memoryTree.daily.length === 0 && memoryTree.miscDaily.length === 0 ? (
          <div className={styles.navigatorEmpty}>
            {t("workspace.noFiles")}
          </div>
        ) : (
          <>
            {memoryTree.daily.map((group) => (
              <div key={group.date}>
                <div className={styles.memoryGroupHeader}>
                  <FolderOpenOutlined />
                  <span>{group.date}</span>
                </div>
                {group.root && (
                  <MemoryRow
                    file={group.root}
                    label={`${group.date}.md`}
                    level={1}
                    selected={selectedPath === `memory/${group.root.filename}`}
                    onClick={() => onDailyMemoryClick(group.root!)}
                  />
                )}
                {group.children.map((child) => (
                  <MemoryRow
                    key={child.filename}
                    file={child}
                    label={child.filename.split("/").slice(1).join("/")}
                    level={1}
                    selected={selectedPath === `memory/${child.filename}`}
                    onClick={() => onDailyMemoryClick(child)}
                  />
                ))}
              </div>
            ))}
            {memoryTree.miscDaily.map((daily) => (
              <MemoryRow
                key={daily.filename}
                file={daily}
                label={daily.filename}
                level={0}
                selected={selectedPath === `memory/${daily.filename}`}
                onClick={() => onDailyMemoryClick(daily)}
              />
            ))}
          </>
        )}
      </div>
    );
  };

  const renderDigest = () => {
    const selectedPath = activeTabPath;
    return (
      <DigestNode
        node={memoryTree.digestRoot}
        level={0}
        selectedPath={selectedPath}
        onSelect={(path) => {
          const file = dailyMemories.find((f) => f.filename === path);
          if (file) onDailyMemoryClick(file);
        }}
      />
    );
  };

  const sources: Array<{ id: FilesSource; label: string }> = [
    { id: "workspace", label: t("files.workspace") },
    { id: "profile", label: t("files.profile") },
    { id: "daily", label: t("files.daily") },
    { id: "digest", label: t("files.digest") },
  ];

  return (
    <aside className={styles.navigator} aria-label={t("files.navigator")}>
      <div className={styles.directoryBar}>
        <span
          className={styles.directoryPath}
          title={workspacePath || undefined}
        >
          {workspacePath || t("workspace.workspacePath")}
        </span>
        <div className={styles.directoryActions}>
          <button
            type="button"
            className={styles.iconButton}
            onClick={onRefresh}
            aria-label={t("common.refresh")}
          >
            <ReloadOutlined />
          </button>
          <button
            type="button"
            className={styles.iconButton}
            onClick={() => uploadRef.current?.click()}
            aria-label={t("common.upload")}
            disabled={uploading}
          >
            <UploadOutlined />
          </button>
        </div>
      </div>
      <input
        ref={uploadRef}
        type="file"
        multiple
        hidden
        onChange={(event) => {
          const selected = Array.from(event.target.files ?? []);
          event.target.value = "";
          if (selected.length > 0) void runUpload(selected);
        }}
      />
      <div className={styles.sourceTabs} role="tablist">
        {sources.map((item) => (
          <button
            type="button"
            role="tab"
            key={item.id}
            aria-selected={source === item.id}
            className={`${styles.sourceTab} ${
              source === item.id ? styles.sourceTabActive : ""
            }`}
            onClick={() => onSourceChange(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <div className={styles.navigatorBody}>
        {source === "workspace" && renderWorkspace()}
        {source === "profile" && renderProfile()}
        {source === "daily" && renderDaily()}
        {source === "digest" && renderDigest()}
      </div>
      <Modal
        className={styles.conflictModal}
        open={pendingUploads !== null}
        title={t("files.uploadConflictTitle")}
        footer={null}
        centered
        onCancel={() => {
          setPendingUploads(null);
          setConflictingNames([]);
        }}
      >
        <p className={styles.conflictDescription}>
          {t("files.uploadConflictDescription", {
            files: conflictingNames.join(", "),
          })}
        </p>
        <div className={styles.conflictChoices}>
          {(["rename", "skip", "overwrite"] as const).map((policy) => (
            <button
              type="button"
              key={policy}
              className={styles.conflictChoice}
              disabled={uploading}
              onClick={() => {
                if (pendingUploads) void runUpload(pendingUploads, policy);
              }}
            >
              <strong>
                {t(
                  `files.conflict${policy[0].toUpperCase()}${policy.slice(1)}`,
                )}
              </strong>
              <span>
                {t(
                  `files.conflict${policy[0].toUpperCase()}${policy.slice(
                    1,
                  )}Description`,
                )}
              </span>
            </button>
          ))}
        </div>
      </Modal>
    </aside>
  );
}
