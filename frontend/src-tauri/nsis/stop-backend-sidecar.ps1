param(
    [Parameter(Mandatory = $true)]
    [string]$InstallDir
)

# Stop Majo backend processes launched from *this* install directory so the
# installer can overwrite their files. The backend is a Java sidecar
# (jre\bin\java.exe -jar majo-backend.jar); a leftover process keeps its jar
# memory-mapped, which locks the file on Windows and makes the installer show
# the cryptic native "can't write file" dialog.
#
# Scoping to $InstallDir + the majo-backend.jar command line leaves other Java
# processes (and a coexisting install) untouched.
#
# Must stay ConstrainedLanguage-safe (WDAC/AppLocker): use only cmdlets,
# operators and core string methods -- never [System.*] static calls.
#
# Exit 0 when no scoped backend remains, 1 while one is still running.

$ErrorActionPreference = "SilentlyContinue"

$root = $InstallDir.TrimEnd("\") + "\"

function Get-ScopedBackendIds {
    $procs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object {
            $_.ExecutablePath -and
            ($_.ExecutablePath -like ($root + "*")) -and
            ($_.CommandLine -like "*majo-backend.jar*")
        }
    return @($procs | ForEach-Object { $_.ProcessId } | Sort-Object -Unique)
}

$ids = Get-ScopedBackendIds
foreach ($processId in $ids) {
    Stop-Process -Id $processId -Force
}
if ($ids.Count -gt 0) {
    Wait-Process -Id $ids -Timeout 8
}

if ((Get-ScopedBackendIds).Count -gt 0) {
    exit 1
}
exit 0
