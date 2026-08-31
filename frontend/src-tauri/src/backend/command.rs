//! Backend command construction for development and packaged builds.

use std::path::{Path, PathBuf};

#[cfg(not(debug_assertions))]
use tauri::Manager;
use tauri_plugin_shell::{process::Command, ShellExt};

/// Strips the Windows long-path prefix (`\\?\`) from a path returned by
/// `resource_dir()`.
///
/// On Windows, Tauri's `current_exe()` canonicalizes to an extended-length
/// path like `\\?\D:\...`. Java's launcher rejects that prefix for `-jar`
/// arguments (`Unable to access jarfile`), so the packaged sidecar command
/// must use plain `D:\...` paths.
#[cfg(windows)]
fn strip_windows_prefix(path: &Path) -> PathBuf {
    let text = path.to_string_lossy();
    if let Some(stripped) = text.strip_prefix("\\\\?\\") {
        PathBuf::from(stripped)
    } else {
        path.to_path_buf()
    }
}

#[cfg(not(windows))]
fn strip_windows_prefix(path: &Path) -> PathBuf {
    path.to_path_buf()
}
/// Java runtime paths used for the sidecar command.
///
/// The desktop app ships a jlink-customized JRE (see
/// `scripts/pack-tauri/build-desktop.ps1`) plus the backend fat jar under
/// `binaries/`, so end-user machines do not need Java installed.
const BUNDLED_JRE_DIR: &str = "binaries/jre";
const BUNDLED_BACKEND_JAR: &str = "binaries/majo-backend.jar";

/// Port the desktop sidecar runs on (browser deployments keep 18789; the
/// desktop shell overrides it via env so it never collides with local apps).
pub(crate) const DESKTOP_SERVER_PORT: &str = "1911";

/// AppCDS archive generated at build time (see build-desktop.ps1). Speeds up
/// backend startup by pre-loading application classes.
const BUNDLED_CDS_ARCHIVE: &str = "binaries/app.jsa";

/// Builds the command used to start the Java backend sidecar in dev mode.
///
/// Dev expects a locally-built jar (`backend/target/majo-backend.jar`); if it
/// is missing the backend fails fast with a clear message instead of spawning
/// a broken process.
#[cfg(debug_assertions)]
pub(super) fn create(app: &tauri::AppHandle) -> Result<Command, String> {
    let repo_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let backend_jar = repo_root.join("backend/target/majo-backend.jar");

    if !backend_jar.is_file() {
        return Err(format!(
            "backend jar not found at {} — run `mvn -f backend/pom.xml package -DskipTests` first",
            backend_jar.display()
        ));
    }

    let java = local_java_command();
    log::info!(
        "[backend] dev command: {} -jar {} cwd={}",
        java.display(),
        backend_jar.display(),
        repo_root.display(),
    );
    Ok(app
        .shell()
        .command(java)
        .args(["-jar", backend_jar.to_str().unwrap_or_default()])
        .current_dir(repo_root))
}

/// Builds the command used to start the bundled Java backend sidecar.
///
/// The packaged app embeds a jlink JRE and the backend jar under
/// `binaries/`; the backend runs from the resource directory so its relative
/// data paths (e.g. `data/majo/`) stay inside the app's data area.
#[cfg(not(debug_assertions))]
pub(super) fn create(app: &tauri::AppHandle) -> Result<Command, String> {
    let resource_dir = app
        .path()
        .resource_dir()
        .map_err(|err| format!("failed to resolve resource directory: {err}"))?;
    // Java cannot open `\\?\`-prefixed paths; normalize to a plain path.
    let resource_dir = strip_windows_prefix(&resource_dir);

    let java = bundled_java(&resource_dir)?;
    let jar = resource_dir.join(BUNDLED_BACKEND_JAR);
    if !jar.is_file() {
        return Err(format!(
            "backend jar not found at {} — the app bundle is incomplete",
            jar.display()
        ));
    }

    log::info!(
        "[backend] packaged command: {} -jar {} cwd={}",
        java.display(),
        jar.display(),
        resource_dir.display(),
    );

    // JVM flags: AppCDS archive (if bundled), C1-only JIT for faster boot,
    // and a heap cap sized for the desktop sidecar.
    let mut args: Vec<String> = Vec::new();
    let cds = resource_dir.join(BUNDLED_CDS_ARCHIVE);
    if cds.is_file() {
        // Single-arg form `-XX:SharedArchiveFile=path` (the shell plugin may
        // split separate key/value elements, which java rejects).
        args.push(format!("-XX:SharedArchiveFile={}", cds.to_string_lossy()));
    }
    args.push("-XX:TieredStopAtLevel=1".into());
    args.push("-Xmx1g".into());
    args.push("-jar".into());
    args.push(jar.to_string_lossy().into_owned());

    Ok(app
        .shell()
        .command(java)
        .args(args)
        .current_dir(&resource_dir))
}

/// Path to the bundled JRE's java executable inside the resource dir.
#[cfg(not(debug_assertions))]
fn bundled_java(resource_dir: &Path) -> Result<PathBuf, String> {
    let java = if cfg!(windows) {
        resource_dir
            .join(BUNDLED_JRE_DIR)
            .join("bin/java.exe")
    } else {
        resource_dir
            .join(BUNDLED_JRE_DIR)
            .join("bin/java")
    };
    if java.is_file() {
        Ok(java)
    } else {
        Err(format!(
            "bundled JRE not found at {} — the app bundle is incomplete",
            java.display()
        ))
    }
}

/// Pick the Java launcher to use in dev mode: `JAVA_HOME/bin/java` if set,
/// otherwise `java` from PATH.
#[cfg(debug_assertions)]
fn local_java_command() -> PathBuf {
    if let Some(java_home) = std::env::var_os("JAVA_HOME") {
        let home_java = PathBuf::from(&java_home).join("bin/java.exe");
        if cfg!(windows) && home_java.is_file() {
            return home_java;
        }
        let home_java = PathBuf::from(&java_home).join("bin/java");
        if home_java.is_file() {
            return home_java;
        }
    }
    PathBuf::from("java")
}
