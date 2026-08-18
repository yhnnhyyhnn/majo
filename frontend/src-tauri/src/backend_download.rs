//! Native downloads for files served by the bundled local backend.

use std::{collections::HashMap, net::IpAddr, path::PathBuf, time::Duration};

use futures_util::TryStreamExt;
use reqwest::{
    header::{HeaderMap, HeaderName, HeaderValue},
    Url,
};
use serde::Deserialize;
use tokio::{
    fs::File,
    io::{AsyncReadExt, AsyncWriteExt, BufWriter},
};

const BACKEND_DOWNLOAD_CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const BACKEND_DOWNLOAD_TOTAL_TIMEOUT: Duration = Duration::from_secs(30 * 60);

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DownloadBackendFileRequest {
    url: String,
    file_path: String,
    headers: Option<HashMap<String, String>>,
}

/// Stream a local backend response to the user-selected file path without using system proxies.
#[tauri::command]
pub(crate) async fn download_backend_file(
    request: DownloadBackendFileRequest,
) -> Result<(), String> {
    let url = parse_local_backend_url(&request.url)?;
    let file_path = parse_file_path(&request.file_path)?;
    let headers = parse_headers(request.headers.unwrap_or_default())?;

    let response = reqwest::Client::builder()
        .no_proxy()
        .connect_timeout(BACKEND_DOWNLOAD_CONNECT_TIMEOUT)
        .timeout(BACKEND_DOWNLOAD_TOTAL_TIMEOUT)
        .build()
        .map_err(|err| format!("failed to create download client: {err}"))?
        .get(url)
        .headers(headers)
        .send()
        .await
        .map_err(|err| format!("download request failed: {err}"))?;

    if !response.status().is_success() {
        return Err(format!(
            "download request failed with status code {}",
            response.status()
        ));
    }

    let mut file = BufWriter::new(
        File::create(&file_path)
            .await
            .map_err(|err| format!("failed to create file: {err}"))?,
    );
    let mut stream = response.bytes_stream();

    while let Some(chunk) = stream
        .try_next()
        .await
        .map_err(|err| format!("failed to read response stream: {err}"))?
    {
        file.write_all(&chunk)
            .await
            .map_err(|err| format!("failed to write file: {err}"))?;
    }

    file.flush()
        .await
        .map_err(|err| format!("failed to flush file: {err}"))
}

fn parse_local_backend_url(url: &str) -> Result<Url, String> {
    let parsed = Url::parse(url).map_err(|err| format!("invalid download URL: {err}"))?;
    if parsed.scheme() != "http" {
        return Err("download URL protocol is not supported".into());
    }
    if !is_loopback_host(&parsed) {
        return Err("download URL must target the local backend".into());
    }
    Ok(parsed)
}

fn is_loopback_host(url: &Url) -> bool {
    match url.host_str() {
        Some(host) if host.eq_ignore_ascii_case("localhost") => true,
        Some(host) => host
            .trim_matches(['[', ']'])
            .parse::<IpAddr>()
            .map(|ip| ip.is_loopback())
            .unwrap_or(false),
        None => false,
    }
}

fn parse_file_path(file_path: &str) -> Result<PathBuf, String> {
    if file_path.trim().is_empty() {
        return Err("download file path is empty".into());
    }
    Ok(PathBuf::from(file_path))
}

fn parse_headers(headers: HashMap<String, String>) -> Result<HeaderMap, String> {
    let mut header_map = HeaderMap::new();
    for (name, value) in headers {
        let header_name = HeaderName::from_bytes(name.as_bytes())
            .map_err(|err| format!("invalid download header name: {err}"))?;
        let header_value = HeaderValue::from_str(&value)
            .map_err(|err| format!("invalid download header value: {err}"))?;
        header_map.insert(header_name, header_value);
    }
    Ok(header_map)
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::{get_coding_directory, parse_local_backend_url};

    /// Serialize tests that mutate process environment variables.
    static ENV_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn accepts_loopback_backend_urls() {
        assert!(parse_local_backend_url("http://127.0.0.1:54377/api/backups/id/export").is_ok());
        assert!(parse_local_backend_url("http://localhost:54377/api/workspace/download").is_ok());
        assert!(parse_local_backend_url("http://[::1]:54377/api/workspace/download").is_ok());
    }

    #[test]
    fn rejects_remote_download_urls() {
        assert!(parse_local_backend_url("https://example.com/file.zip").is_err());
        assert!(parse_local_backend_url("http://192.168.1.20/file.zip").is_err());
    }

    #[test]
    fn rejects_non_http_download_urls() {
        assert!(parse_local_backend_url("file:///C:/tmp/backup.zip").is_err());
        assert!(parse_local_backend_url("mailto:support@example.com").is_err());
    }

    #[test]
    fn coding_directory_uses_profile_workspace_dir() {
        let _guard = ENV_LOCK.lock().unwrap();
        let temp = tempfile::tempdir().unwrap();
        let working_dir = temp.path();

        let workspace_dir = working_dir.join("workspaces/test-agent");
        std::fs::create_dir_all(&workspace_dir).unwrap();

        // Majo agents.json layout: profiles hold workspace_dir directly.
        std::fs::write(
            working_dir.join("agents.json"),
            serde_json::json!({
                "schema_version": "agents.v1",
                "profiles": {
                    "test-agent": {
                        "id": "test-agent",
                        "workspace_dir": workspace_dir.to_str().unwrap(),
                        "enabled": true,
                    }
                }
            })
            .to_string(),
        )
        .unwrap();

        std::env::set_var("MAJO_WORKING_DIR", working_dir);
        let result = get_coding_directory(Some("test-agent")).unwrap();
        std::env::remove_var("MAJO_WORKING_DIR");

        assert_eq!(result, workspace_dir);
    }

    #[test]
    fn coding_directory_falls_back_to_workspaces_dir() {
        let _guard = ENV_LOCK.lock().unwrap();
        let temp = tempfile::tempdir().unwrap();
        let working_dir = temp.path();

        // No agents.json yet: fall back to {working_dir}/workspaces/{agent_id}.
        std::env::set_var("MAJO_WORKING_DIR", working_dir);
        let result = get_coding_directory(Some("test-agent")).unwrap();
        std::env::remove_var("MAJO_WORKING_DIR");

        assert_eq!(
            result,
            working_dir.join("workspaces/test-agent")
        );
    }
    }
}

// ---------------------------------------------------------------------------
// Local file reading for offline binary file preview
// ---------------------------------------------------------------------------

/// Maximum file size for binary preview (50 MB, matching the Python backend limit).
const BINARY_FILE_MAX_BYTES: u64 = 50 * 1024 * 1024;

/// Read a binary file from the local workspace for offline preview.
///
/// This command enables the frontend to display images, PDFs, and other binary
/// files in the code editor preview mode when the backend API is unavailable
/// (e.g., offline desktop usage).
///
/// The `file_path` parameter is a relative path within the coding project directory.
/// The `agent_id` parameter specifies which agent's workspace to use (from frontend state).
#[tauri::command]
pub(crate) async fn read_workspace_binary_file(
    file_path: String,
    agent_id: Option<String>,
) -> Result<tauri::ipc::Response, String> {
    let absolute_path = resolve_workspace_file_path(&file_path, agent_id.as_deref())?;

    if !absolute_path.is_file() {
        return Err(format!("path is not a file: {}", absolute_path.display()));
    }

    // Enforce size limit to prevent OOM on large files
    let metadata = tokio::fs::metadata(&absolute_path)
        .await
        .map_err(|err| format!("failed to read file metadata: {err}"))?;

    if metadata.len() > BINARY_FILE_MAX_BYTES {
        return Err(format!(
            "file too large for preview ({} MB > {} MB limit)",
            metadata.len() / 1024 / 1024,
            BINARY_FILE_MAX_BYTES / 1024 / 1024,
        ));
    }

    let mut file = File::open(&absolute_path)
        .await
        .map_err(|err| format!("failed to open file: {err}"))?;

    let mut buffer = Vec::with_capacity(metadata.len() as usize);
    file.read_to_end(&mut buffer)
        .await
        .map_err(|err| format!("failed to read file: {err}"))?;

    Ok(tauri::ipc::Response::new(buffer))
}

/// Resolve a relative workspace file path to an absolute path.
///
/// Reads the Majo config to determine the coding project directory (or workspace
/// directory if no custom project is set), then safely joins the relative path to
/// prevent path traversal attacks.
///
/// If `agent_id` is provided, uses that agent's config; otherwise falls back to
/// the active agent in config.json.
fn resolve_workspace_file_path(
    relative_path: &str,
    agent_id: Option<&str>,
) -> Result<PathBuf, String> {
    if relative_path.trim().is_empty() {
        return Err("file path is empty".into());
    }

    let coding_dir = get_coding_directory(agent_id)?;

    // Safe join: resolve the path and ensure it stays within coding directory
    let target = coding_dir.join(relative_path);
    let canonical_target = target.canonicalize().map_err(|err| {
        format!("failed to resolve file path '{}': {err}", target.display())
    })?;

    let canonical_coding_dir = coding_dir.canonicalize().map_err(|err| {
        format!(
            "failed to resolve coding directory '{}': {err}",
            coding_dir.display()
        )
    })?;

    if !canonical_target.starts_with(&canonical_coding_dir) {
        return Err(format!(
            "path traversal detected: '{}' resolves outside coding directory",
            relative_path
        ));
    }

    Ok(canonical_target)
}

/// Get the coding project directory from Majo configuration.
///
/// Resolution order:
/// 1. `MAJO_WORKING_DIR` environment variable (same as the Java backend)
/// 2. Per-user data dir (`{data_dir}/majo`), matching the desktop sidecar
///
/// Then reads the agent profile from root `agents.json`:
/// - If the agent profile has `workspace_dir`, use it
/// - Otherwise fall back to `{working_dir}/workspaces/{agent_id}`
///
/// If `agent_id` is None, uses "default" (Majo's default agent id).
fn get_coding_directory(agent_id: Option<&str>) -> Result<PathBuf, String> {
    let working_dir = if let Ok(dir) = std::env::var("MAJO_WORKING_DIR") {
        if dir.trim().is_empty() {
            return Err("MAJO_WORKING_DIR is set but empty".into());
        }
        PathBuf::from(dir)
    } else if let Some(data_dir) = dirs::data_dir() {
        data_dir.join("majo")
    } else {
        return Err("failed to resolve data directory".into());
    };

    let agents_path = working_dir.join("agents.json");
    if !agents_path.is_file() {
        return Ok(working_dir.join("workspaces").join(
            agent_id.unwrap_or("default"),
        ));
    }

    let agents_content = std::fs::read_to_string(&agents_path)
        .map_err(|err| format!("failed to read agents.json: {err}"))?;

    let config: serde_json::Value = serde_json::from_str(&agents_content)
        .map_err(|err| format!("failed to parse agents.json: {err}"))?;

    let target_agent = agent_id.unwrap_or("default");

    let workspace_dir = config
        .get("profiles")
        .and_then(|p| p.get(target_agent))
        .and_then(|profile| profile.get("workspace_dir"))
        .and_then(|d| d.as_str())
        .map(|d| expand_tilde(d))
        .unwrap_or_else(|| working_dir.join("workspaces").join(target_agent));

    Ok(workspace_dir)
}

/// Expand `~` at the start of a path to the user's home directory.
fn expand_tilde(path: &str) -> PathBuf {
    if path.starts_with("~/") || path.starts_with("~\\") {
        if let Some(home) = dirs::home_dir() {
            return home.join(&path[2..]);
        }
    }
    PathBuf::from(path)
}
