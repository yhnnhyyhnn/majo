param(
    [switch]$SkipBackendBuild,
    [switch]$SkipFrontendBuild,
    [switch]$SkipTauriBuild
)

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$FrontendDir = Join-Path $RepoRoot "frontend"
$BackendDir = Join-Path $RepoRoot "backend"
$TauriDir = Join-Path $FrontendDir "src-tauri"
$BinariesDir = Join-Path $TauriDir "binaries"
$StaticDir = Join-Path $BackendDir "src/main/resources/static"

function Step($Name) { Write-Host "`n=== $Name ===" -ForegroundColor Cyan }

# ---------------------------------------------------------------------------
# 1. Build the frontend (produces frontend/dist)
# ---------------------------------------------------------------------------
if (-not $SkipFrontendBuild) {
    Step "Building frontend (npm run build:prod)"
    Push-Location $FrontendDir
    try {
        npm run build:prod
        if ($LASTEXITCODE -ne 0) { throw "frontend build failed" }
    } finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# 2. Copy the frontend dist into the backend's static resources so the jar
#    serves the SPA (same layout as the Docker build).
# ---------------------------------------------------------------------------
Step "Copying frontend dist into backend static resources"
$FrontendDist = Join-Path $FrontendDir "dist"
if (-not (Test-Path $FrontendDist)) { throw "frontend dist not found: $FrontendDist" }
if (Test-Path $StaticDir) { Remove-Item -Recurse -Force $StaticDir }
New-Item -ItemType Directory -Path $StaticDir -Force | Out-Null
Copy-Item -Recurse -Force (Join-Path $FrontendDist "*") $StaticDir
Write-Host "Copied $FrontendDist -> $StaticDir"

# ---------------------------------------------------------------------------
# 3. Build the backend fat jar (majo-backend.jar)
# ---------------------------------------------------------------------------
if (-not $SkipBackendBuild) {
    Step "Building backend jar (mvn package)"
    Push-Location $BackendDir
    try {
        mvn package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "backend build failed" }
    } finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# 4. jlink a minimal JRE into src-tauri/binaries/jre
# ---------------------------------------------------------------------------
Step "Building minimal JRE with jlink"
$JarPath = Join-Path $BackendDir "target/majo-backend.jar"
if (-not (Test-Path $JarPath)) { throw "backend jar not found: $JarPath" }
$JreDir = Join-Path $BinariesDir "jre"
if (Test-Path $JreDir) { Remove-Item -Recurse -Force $JreDir }

$Jlink = "jlink"
if ($env:JAVA_HOME) { $Jlink = Join-Path $env:JAVA_HOME "bin/jlink.exe" }

# java.base + the modules Spring Boot needs at runtime. If the app ever
# reports a missing module at startup, add it here (jlink fails fast with
# the exact module name).
& $Jlink --add-modules `
    java.base,java.logging,java.naming,java.sql,java.management,`
    java.security.jgss,java.instrument,jdk.unsupported,jdk.crypto.ec,`
    java.desktop,java.xml,java.net.http,jdk.crypto.cryptoki `
    --strip-debug --no-header-files --no-man-pages `
    --output $JreDir
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }
Write-Host "JRE created at $JreDir"

# ---------------------------------------------------------------------------
# 5. Copy the backend jar into src-tauri/binaries
# ---------------------------------------------------------------------------
Step "Copying backend jar into src-tauri/binaries"
New-Item -ItemType Directory -Path $BinariesDir -Force | Out-Null
Copy-Item -Force $JarPath (Join-Path $BinariesDir "majo-backend.jar")
Write-Host "Copied $JarPath -> $BinariesDir\majo-backend.jar"

# ---------------------------------------------------------------------------
# 6. Build the Tauri app (npm run build:tauri-bootstrap + tauri build)
# ---------------------------------------------------------------------------
if (-not $SkipTauriBuild) {
    Step "Building Tauri desktop app"
    Push-Location $FrontendDir
    try {
        npm run build:tauri-bootstrap
        if ($LASTEXITCODE -ne 0) { throw "tauri bootstrap build failed" }
        npx tauri build
        if ($LASTEXITCODE -ne 0) { throw "tauri build failed" }
    } finally { Pop-Location }
    Write-Host "`nDesktop bundle ready under $FrontendDir\src-tauri\target\release\bundle" -ForegroundColor Green
} else {
    Write-Host "`nSkipped tauri build. Artifacts staged: binaries/jre + binaries/majo-backend.jar + static SPA." -ForegroundColor Yellow
    Write-Host "Run: cd frontend; npm run build:tauri-bootstrap; npx tauri build" -ForegroundColor Yellow
}
