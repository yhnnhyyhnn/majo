import { request } from "../request";
import { getApiUrl } from "../config";
import { buildAuthHeaders } from "../authHeaders";
import {
  DownloadCancelledError,
  downloadFileFromUrl,
} from "../../utils/downloadFileFromUrl";
import type {
  BackupMeta,
  BackupTrustMode,
  BackupDetail,
  BackupProgressEvent,
  BackupConflictResponse,
  CreateBackupRequest,
  RestoreBackupRequest,
  RestoreBackupResponse,
  DeleteBackupsResponse,
} from "../types/backup";

export const backupApi = {
  listBackups: () => request<BackupMeta[]>("/backups"),

  getBackup: (id: string) => request<BackupDetail>(`/backups/${id}`),

  createBackupStream: async (
    data: CreateBackupRequest,
    onEvent: (event: BackupProgressEvent) => void,
    signal?: AbortSignal,
  ): Promise<BackupMeta> => {
    const url = getApiUrl("/backups/stream");
    const res = await fetch(url, {
      method: "POST",
      headers: { ...buildAuthHeaders(), "Content-Type": "application/json" },
      body: JSON.stringify(data),
      signal,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Request failed: ${res.status}`);
    }

    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let meta: BackupMeta | null = null;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split("\n\n");
      buffer = chunks.pop() ?? "";
      for (const chunk of chunks) {
        if (!chunk.startsWith("data: ")) continue;
        const event = JSON.parse(chunk.slice(6)) as BackupProgressEvent;
        onEvent(event);
        if (event.type === "done") meta = event.meta;
        if (event.type === "error") throw new Error(event.message);
      }
    }

    if (!meta) throw new Error("No completion event received");
    return meta;
  },

  /** Start an application-owned backup job; resolves with the initial snapshot. */
  startBackupJob: async (
    data: CreateBackupRequest,
  ): Promise<{ job_id: string }> => {
    const res = await fetch(getApiUrl("/backups/jobs"), {
      method: "POST",
      headers: { ...buildAuthHeaders(), "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });
    if (res.status === 409) {
      throw new Error("A backup job is already running");
    }
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Request failed: ${res.status}`);
    }
    return res.json();
  },

  cancelBackupJob: async (jobId: string): Promise<void> => {
    await request(`/backups/jobs/${encodeURIComponent(jobId)}/cancel`, {
      method: "POST",
    }).catch(() => undefined);
  },

  /**
   * Observe a backup job via the reconnectable SSE snapshot stream.
   * Resolves with the final meta on completion; rejects on failure/cancel.
   */
  streamBackupJobEvents: (
    jobId: string,
    onEvent: (event: BackupProgressEvent) => void,
    signal?: AbortSignal,
  ): Promise<BackupMeta> =>
    new Promise((resolve, reject) => {
      const url = getApiUrl(
        `/backups/jobs/${encodeURIComponent(jobId)}/events`,
      );
      const eventSource = new EventSource(url);
      const cleanup = () => eventSource.close();
      signal?.addEventListener("abort", () => {
        cleanup();
        reject(new DOMException("Aborted", "AbortError"));
      });
      eventSource.onmessage = (messageEvent) => {
        try {
          const snapshot = JSON.parse(messageEvent.data) as {
            status: string;
            error?: string;
            percent?: number;
            current_agent?: string | null;
            agent_index?: number;
            total_agents?: number;
            phase?: string;
            result?: BackupMeta;
          };
          if (snapshot.status === "completed" && snapshot.result) {
            cleanup();
            resolve(snapshot.result);
            return;
          }
          if (snapshot.status === "failed") {
            cleanup();
            reject(new Error(snapshot.error || "Backup failed"));
            return;
          }
          if (snapshot.status === "cancelled") {
            cleanup();
            reject(new DOMException("Backup cancelled", "AbortError"));
            return;
          }
          // Map the snapshot onto the legacy progress-event shape so the
          // existing progress UI keeps working unchanged.
          onEvent({
            type:
              snapshot.phase === "finalizing"
                ? "saving"
                : snapshot.phase === "agents"
                  ? "agent"
                  : "start",
            percent: snapshot.percent ?? 0,
            agent_id: snapshot.current_agent ?? undefined,
            index: snapshot.agent_index,
            total: snapshot.total_agents,
          } as BackupProgressEvent);
        } catch {
          // Ignore malformed frames
        }
      };
      eventSource.onerror = () => {
        cleanup();
        reject(new Error("Backup job stream disconnected"));
      };
    }),

  restoreBackup: (id: string, data: RestoreBackupRequest) =>
    request<RestoreBackupResponse>(`/backups/${id}/restore`, {
      method: "POST",
      body: JSON.stringify(data),
    }),

  deleteBackups: (ids: string[]) =>
    request<DeleteBackupsResponse>("/backups/delete", {
      method: "POST",
      body: JSON.stringify({ ids }),
    }),

  exportBackup: async (id: string, name: string) => {
    const url = getApiUrl(`/backups/${id}/export`);

    try {
      await downloadFileFromUrl(url, `${name}.zip`, {
        headers: buildAuthHeaders(),
        errorMessage: "Export failed",
      });
    } catch (error) {
      if (error instanceof DownloadCancelledError) {
        return;
      }
      throw error;
    }
  },

  importBackup: async (
    file: File,
    options: { trustMode?: BackupTrustMode } = {},
  ): Promise<BackupMeta> => {
    const formData = new FormData();
    formData.append("file", file);
    if (options.trustMode) {
      formData.append("trust_mode", options.trustMode);
    }
    const url = getApiUrl("/backups/import");
    const res = await fetch(url, {
      method: "POST",
      headers: buildAuthHeaders(),
      body: formData,
    });
    if (res.status === 409) {
      const body: BackupConflictResponse = await res.json();
      const err = new Error("backup_conflict") as Error & {
        conflict: BackupConflictResponse;
      };
      err.conflict = body;
      throw err;
    }
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Import failed: ${res.status}`);
    }
    return res.json();
  },

  resolveImportConflict: async (pendingToken: string): Promise<BackupMeta> => {
    const formData = new FormData();
    formData.append("pending_token", pendingToken);
    const url = getApiUrl("/backups/import");
    const res = await fetch(url, {
      method: "POST",
      headers: buildAuthHeaders(),
      body: formData,
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Import failed: ${res.status}`);
    }
    return res.json();
  },
};
