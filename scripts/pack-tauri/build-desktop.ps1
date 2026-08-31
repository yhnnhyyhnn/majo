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
$JreModules = @(
    'java.base','java.logging','java.naming','java.sql','java.management',
    'java.security.jgss','java.instrument','jdk.unsupported','jdk.crypto.ec',
    'java.desktop','java.xml','java.net.http','jdk.crypto.cryptoki'
) -join ','
& $Jlink --add-modules $JreModules --strip-debug --no-header-files --no-man-pages --output $JreDir
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }
Write-Host "JRE created at $JreDir"

# ---------------------------------------------------------------------------
# 5. Copy the backend jar into src-tauri/binaries
# ---------------------------------------------------------------------------
Step "Copying backend jar into src-tauri/binaries"
New-Item -ItemType Directory -Path $BinariesDir -Force | Out-Null
# Remove any stale target first (a leftover directory named like the jar
# makes Copy-Item nest the real jar inside it and breaks the bundle).
$JarTarget = Join-Path $BinariesDir "majo-backend.jar"
if (Test-Path $JarTarget) { Remove-Item -Recurse -Force $JarTarget }
Copy-Item -Force $JarPath $JarTarget
Write-Host "Copied $JarPath -> $JarTarget"

# ---------------------------------------------------------------------------
# 5b. AppCDS training run: build app.jsa so the sidecar boots much faster.
# ---------------------------------------------------------------------------
Step "Building AppCDS archive (app.jsa)"
$CdsArchive = Join-Path $BinariesDir "app.jsa"
if (Test-Path $CdsArchive) { Remove-Item -Force $CdsArchive }
$JavaExe = Join-Path $JreDir "bin/java.exe"
if (-not (Test-Path $JavaExe)) { $JavaExe = Join-Path $JreDir "bin/java" }
# Dump the base CDS archive first (jlink images ship without one), then run
# a full backend boot with -XX:ArchiveClassesAtExit to capture app classes.
# spring.context.exit=onRefresh makes the JVM exit right after startup.
& $JavaExe -Xshare:dump 2>$null
$CdsTrainEnv = @{
    MAJO_DESKTOP_APP = "1"
    SERVER_PORT = $null
    SPRING_PROFILES_ACTIVE = "desktop"
    MAJO_WORKING_DIR = (Join-Path $env:TEMP "majo-cds-train")
}
Push-Location $BinariesDir
New-Item -ItemType Directory -Path $CdsTrainEnv.MAJO_WORKING_DIR -Force | Out-Null
try {
    $env:SERVER_PORT = "1911"
    $env:SPRING_PROFILES_ACTIVE = "desktop"
    $env:MAJO_DESKTOP_APP = "1"
    $env:MAJO_WORKING_DIR = $CdsTrainEnv.MAJO_WORKING_DIR
    & $JavaExe "-XX:ArchiveClassesAtExit=$CdsArchive" "-Dspring.context.exit=onRefresh" -jar $JarTarget 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 130) {
        Write-Host "WARN: CDS training run exited $LASTEXITCODE (continuing without app.jsa)" -ForegroundColor Yellow
    }
} finally {
    Remove-Item Env:SERVER_PORT, Env:SPRING_PROFILES_ACTIVE, Env:MAJO_DESKTOP_APP, Env:MAJO_WORKING_DIR -ErrorAction SilentlyContinue
    Pop-Location
    Remove-Item -Recurse -Force $CdsTrainEnv.MAJO_WORKING_DIR -ErrorAction SilentlyContinue
}
if (Test-Path $CdsArchive) {
    Write-Host "AppCDS archive created: $CdsArchive"
} else {
    Write-Host "WARN: app.jsa was not produced; startup will skip CDS." -ForegroundColor Yellow
}
# The base CDS dump (`classes.jsa`) created by -Xshare:dump is only a
# training aid; it is read-only and makes tauri-build's resource copy fail.
# app.jsa already embeds the base + application classes, so remove it.
$BaseJsa = Join-Path $JreDir "lib\server\classes.jsa"
if (Test-Path $BaseJsa) { Remove-Item -Force $BaseJsa }
$BaseJsaAlt = Join-Path $JreDir "bin\server\classes.jsa"
if (Test-Path $BaseJsaAlt) { Remove-Item -Force $BaseJsaAlt }

# ---------------------------------------------------------------------------
# 6. Build the Tauri app (npm run build:tauri-bootstrap + tauri build)
# ---------------------------------------------------------------------------
if (-not $SkipTauriBuild) {
    Step "Building Tauri desktop app"
    # cargo caches build.rs, so `tauri build` may skip re-copying resources
    # into target/release/ after a clean. Force the resources into place so
    # the standalone release exe always finds binaries/jre + the backend jar.
    $ReleaseDir = Join-Path $TauriDir "target\release"
    $ReleaseBinaries = Join-Path $ReleaseDir "binaries"
    if (Test-Path $ReleaseBinaries) { Remove-Item -Recurse -Force $ReleaseBinaries }
    # Drop any runtime data dir created by the CDS training run.
    if (Test-Path (Join-Path $BinariesDir "data")) { Remove-Item -Recurse -Force (Join-Path $BinariesDir "data") }
    Copy-Item -Recurse -Force $BinariesDir $ReleaseBinaries
    Write-Host "Staged resources -> $ReleaseBinaries"
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
