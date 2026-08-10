!include LogicLib.nsh

!macro MAJO_STOP_BACKEND_SIDECAR
  ; The Java backend is a Tauri sidecar, not a user-facing window. A leftover
  ; (possibly orphaned) backend keeps its majo-backend.jar memory-mapped, which
  ; locks it on Windows. The installer then fails to overwrite that file and
  ; shows the cryptic native "can't write file" abort/retry/ignore dialog.
  ;
  ; The helper stops only Java processes whose command line contains
  ; majo-backend.jar and whose executable lives under $INSTDIR, so a coexisting
  ; Majo install is left untouched. It exits non-zero while a scoped backend is
  ; still running; if that persists we surface a friendly retry prompt rather
  ; than the raw OS dialog.
  Push $0
  InitPluginsDir
  File /oname=$PLUGINSDIR\majo-stop-backend-sidecar.ps1 "..\..\..\..\nsis\stop-backend-sidecar.ps1"
  ${Do}
    nsExec::Exec `powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PLUGINSDIR\majo-stop-backend-sidecar.ps1" -InstallDir "$INSTDIR"`
    Pop $0
    ${If} $0 == 0
      ${ExitDo}
    ${EndIf}
    ; Still running (or could not be stopped). Ask the user; default to Cancel
    ; for silent installs.
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "$(majoStopBackendPrompt)" /SD IDCANCEL IDRETRY +2
    Quit
  ${Loop}
  Pop $0
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro MAJO_STOP_BACKEND_SIDECAR
!macroend

!macro NSIS_HOOK_POSTINSTALL
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro MAJO_STOP_BACKEND_SIDECAR
!macroend
