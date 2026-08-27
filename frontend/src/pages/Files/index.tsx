import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { PageHeader } from "@/components/PageHeader";
import { useAgentStore } from "../../stores/agentStore";
import {
  useCurrentActiveTabPath,
  useCurrentTabs,
  useCodingTabsStore,
} from "../../stores/codingTabsStore";
import { useAgentsData } from "../Agent/Workspace/components";
import { workspaceApi } from "../../api/modules/workspace";
import type { DailyMemoryFile, MarkdownFile } from "../../api/types";
import TabbedEditor from "../Coding/TabbedEditor";
import FilesNavigator, { type FilesSource } from "./FilesNavigator";
import styles from "./index.module.less";

export default function FilesPage() {
  const { t } = useTranslation();
  const { selectedAgent } = useAgentStore();
  const agentsData = useAgentsData();
  const [source, setSource] = useState<FilesSource>("workspace");
  const [fileTreeKey, setFileTreeKey] = useState(0);

  const tabs = useCurrentTabs();
  const activeTabPath = useCurrentActiveTabPath();
  const { openTab, closeTab, setActiveTab, setTabContent, setTabDirty } =
    useCodingTabsStore();

  const handleRefresh = useCallback(() => {
    if (source === "workspace") {
      setFileTreeKey((current) => current + 1);
      return;
    }
    agentsData.fetchFiles();
  }, [agentsData.fetchFiles, source]);

  const openInEditor = useCallback(
    (path: string, content: string) => {
      openTab(selectedAgent, { path, content, dirty: false });
      setActiveTab(selectedAgent, path);
    },
    [selectedAgent, openTab, setActiveTab],
  );

  const handleWorkspaceFileSelect = useCallback(
    (path: string, content: string) => openInEditor(path, content),
    [openInEditor],
  );

  const handleMdFileSelect = useCallback(
    async (file: MarkdownFile) => {
      try {
        const data = await workspaceApi.loadFile(file.filename);
        openInEditor(file.filename, data.content);
      } catch {
        openInEditor(file.filename, "");
      }
    },
    [openInEditor],
  );

  const handleDailyMemorySelect = useCallback(
    async (daily: DailyMemoryFile) => {
      const path = `memory/${daily.filename}`;
      try {
        const data = await workspaceApi.loadCodeFile(path);
        openInEditor(path, data.content ?? "");
      } catch {
        openInEditor(path, "");
      }
    },
    [openInEditor],
  );

  const handleGraphFileSelect = useCallback(
    (_graphSource: "daily" | "digest", path: string) => {
      if (!path) return;
      void openInEditor(`memory/${path}`, "");
    },
    [openInEditor],
  );

  const handleTabSelect = useCallback(
    (path: string) => setActiveTab(selectedAgent, path),
    [selectedAgent, setActiveTab],
  );

  const handleTabClose = useCallback(
    (path: string) => {
      const idx = tabs.findIndex((item) => item.path === path);
      closeTab(selectedAgent, path);
      if (activeTabPath === path) {
        const fallback = tabs[idx + 1]?.path ?? tabs[idx - 1]?.path ?? "";
        setActiveTab(selectedAgent, fallback);
      }
    },
    [tabs, activeTabPath, selectedAgent, closeTab, setActiveTab],
  );

  const handleTabDirtyChange = useCallback(
    (path: string, dirty: boolean) => setTabDirty(selectedAgent, path, dirty),
    [selectedAgent, setTabDirty],
  );

  const handleTabContentChange = useCallback(
    (path: string, content: string) =>
      setTabContent(selectedAgent, path, content),
    [selectedAgent, setTabContent],
  );

  useEffect(() => {
    setSource("workspace");
  }, [selectedAgent]);

  return (
    <div className={styles.filesPage}>
      <PageHeader
        className={styles.pageHeader}
        items={[{ title: t("nav.agent") }, { title: t("files.title") }]}
      />
      <div className={styles.content}>
        <FilesNavigator
          source={source}
          onSourceChange={setSource}
          files={agentsData.files}
          activeTabPath={activeTabPath}
          dailyMemories={agentsData.dailyMemories}
          enabledFiles={agentsData.enabledFiles}
          onFileClick={handleMdFileSelect}
          onDailyMemoryClick={handleDailyMemorySelect}
          onToggleFileEnabled={agentsData.handleToggleFileEnabled}
          onReorderFiles={agentsData.handleReorderFiles}
          onWorkspaceFileSelect={handleWorkspaceFileSelect}
          onOpenGraphFile={handleGraphFileSelect}
          onRefresh={handleRefresh}
          fileTreeKey={fileTreeKey}
          agentId={selectedAgent}
        />
        <div className={styles.editor}>
          {tabs.length > 0 ? (
            <TabbedEditor
              tabs={tabs}
              activeTabPath={activeTabPath}
              onTabSelect={handleTabSelect}
              onTabClose={handleTabClose}
              onTabDirtyChange={handleTabDirtyChange}
              onTabContentChange={handleTabContentChange}
            />
          ) : (
            <div className={styles.editorEmpty}>
              {t("workspace.selectFile")}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
