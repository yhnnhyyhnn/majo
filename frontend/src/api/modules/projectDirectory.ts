import { request } from "../request";
import { getApiUrl } from "../config";
import { buildAuthHeaders } from "../authHeaders";

export interface ProjectDirectoryInfo {
  path: string;
  name: string;
  is_workspace_default: boolean;
  workspace_dir?: string;
  exists?: boolean;
}

export interface ProjectListItem {
  path: string;
  name: string;
  is_git: boolean;
  is_active: boolean;
}

export interface BrowseDirsResponse {
  current: string;
  parent: string | null;
  dirs: Array<{ name: string; path: string }>;
  selectable?: boolean;
}

const BASE = "/workspace/coding-project";

export const projectDirectoryApi = {
  get: () => request<ProjectDirectoryInfo>(BASE),

  set: (path: string | null) =>
    request<ProjectDirectoryInfo>(BASE, {
      method: "PUT",
      body: JSON.stringify({ path }),
    }),

  create: (name: string) =>
    request<{ path: string; name: string }>(`${BASE}/create`, {
      method: "POST",
      body: JSON.stringify({ name }),
    }),

  list: () => request<ProjectListItem[]>(`${BASE}/list`),

  importLocal: (path: string, name?: string) =>
    request<{ path: string; name: string }>(`${BASE}/import-local`, {
      method: "POST",
      body: JSON.stringify({ path, name: name || undefined }),
    }),

  uploadZip: async (
    zipFile: File,
    name: string,
  ): Promise<{ path: string; name: string }> => {
    const formData = new FormData();
    formData.append("file", zipFile);
    const res = await fetch(
      getApiUrl(`${BASE}/upload-zip?name=${encodeURIComponent(name)}`),
      {
        method: "POST",
        headers: buildAuthHeaders(),
        body: formData,
      },
    );
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(text || `Upload failed: ${res.status}`);
    }
    return res.json() as Promise<{ path: string; name: string }>;
  },

  browseDirs: (path?: string, showHidden?: boolean) =>
    request<BrowseDirsResponse>(
      `${BASE}/browse-dirs?path=${encodeURIComponent(
        path || "~",
      )}${showHidden ? "&show_hidden=true" : ""}`,
    ),

  cloneStream: (url: string, name?: string): Promise<Response> =>
    fetch(getApiUrl(`${BASE}/clone`), {
      method: "POST",
      headers: {
        ...buildAuthHeaders(),
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ url, name: name || undefined }),
    }),
};
