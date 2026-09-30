package com.agent.coding.sandbox;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Minimal Win32 surface for the AppContainer sandbox (ADR-0012 phase 2):
 * profile lifecycle (userenv), DACL grants (advapi32) and the
 * SECURITY_CAPABILITIES attribute-list process launch (kernel32). Only the
 * functions the unelevated path needs — the elevated service path and the
 * Linux/macOS wrappers are explicitly not in this increment.
 */
final class WinApi {

    private WinApi() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // kernel32 ─ attribute list + pipes + process lifecycle
    interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

        int PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009;
        int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
        int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
        int CREATE_NO_WINDOW = 0x08000000;
        int STARTF_USESTDHANDLES = 0x00000100;
        int WAIT_OBJECT_0 = 0x00000000;
        int WAIT_TIMEOUT = 0x00000102;
        int INFINITE = 0xFFFFFFFF;

        boolean InitializeProcThreadAttributeList(Pointer list, int count,
                                                  int flags, WinDef.DWORDByReference size);

        boolean UpdateProcThreadAttribute(Pointer list, WinDef.DWORD flags,
                                          WinDef.DWORD attribute, Pointer value,
                                          WinNT.SIZE_T size, Pointer previous, Pointer returned);

        void DeleteProcThreadAttributeList(Pointer list);

        boolean CreateProcessW(String applicationName, char[] commandLine,
                               WinBase.SECURITY_ATTRIBUTES processAttributes,
                               WinBase.SECURITY_ATTRIBUTES threadAttributes,
                               boolean inheritHandles, WinDef.DWORD creationFlags,
                               Pointer environment, String currentDirectory,
                               STARTUPINFOEXW startupInfo, WinBase.PROCESS_INFORMATION processInformation);

        boolean CreatePipe(WinNT.HANDLEByReference readPipe, WinNT.HANDLEByReference writePipe,
                           WinBase.SECURITY_ATTRIBUTES pipeAttributes, WinDef.DWORD size);

        boolean ReadFile(WinNT.HANDLE file, byte[] buffer, WinDef.DWORD bytesToRead,
                         IntByReference bytesRead, Pointer overlapped);

        WinDef.DWORD WaitForSingleObject(WinNT.HANDLE handle, WinDef.DWORD milliseconds);

        boolean TerminateProcess(WinNT.HANDLE process, int exitCode);

        boolean GetExitCodeProcess(WinNT.HANDLE process, IntByReference exitCode);

        boolean CloseHandle(WinNT.HANDLE handle);

        com.sun.jna.Pointer LocalFree(com.sun.jna.Pointer pointer);

        int GetOEMCP();
    }

    // userenv ─ AppContainer profile lifecycle
    interface Userenv extends StdCallLibrary {
        Userenv INSTANCE = Native.load("userenv", Userenv.class, W32APIOptions.UNICODE_OPTIONS);

        int ERROR_ALREADY_EXISTS = 0x800700B7;

        int CreateAppContainerProfile(WString containerName, WString displayName,
                                      WString description, Pointer capabilities,
                                      WinDef.DWORD capabilityCount, PointerByReference sid);

        int DeriveAppContainerSidFromAppContainerName(WString containerName, PointerByReference sid);

        int DeleteAppContainerProfile(WString containerName);
    }

    // advapi32 ─ SID string conversion (freed with LocalFree)
    interface SidConversion extends StdCallLibrary {
        SidConversion INSTANCE = Native.load("advapi32", SidConversion.class, W32APIOptions.UNICODE_OPTIONS);

        boolean ConvertStringSidToSidW(String stringSid, PointerByReference sid);

        boolean ConvertSidToStringSidW(Pointer sid, PointerByReference stringSid);
    }

    /** STARTUPINFOEXW — STARTUPINFOW plus the proc-thread attribute list pointer. */
    @Structure.FieldOrder({"StartupInfo", "lpAttributeList"})
    public static class STARTUPINFOEXW extends Structure {
        public WinBase.STARTUPINFO StartupInfo;
        public Pointer lpAttributeList;

        public STARTUPINFOEXW() {
            StartupInfo = new WinBase.STARTUPINFO();
        }
    }

    /** SECURITY_CAPABILITIES — lowbox token definition for the attribute list. */
    @Structure.FieldOrder({"AppContainerSid", "Capabilities", "CapabilityCount", "Reserved"})
    public static class SECURITY_CAPABILITIES extends Structure {
        public Pointer AppContainerSid;
        public Pointer Capabilities; // SID_AND_ATTRIBUTES[]
        public WinDef.DWORD CapabilityCount;
        public WinDef.DWORD Reserved;
    }

    @Structure.FieldOrder({"Sid", "Attributes"})
    public static class SID_AND_ATTRIBUTES extends Structure {
        public Pointer Sid;
        public WinDef.DWORD Attributes; // SE_GROUP_ENABLED = 4 per upstream
    }
}
