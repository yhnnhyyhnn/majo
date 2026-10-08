package com.agent.coding.sandbox;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows AppContainer bridge (ADR-0012 phase 2, unelevated path) — the Java
 * counterpart of QwenPaw's {@code windows_appcontainer_sandbox.py} reduced
 * to the verified core: profile lifecycle, workspace/read/deny DACL grants,
 * and process launch through a SECURITY_CAPABILITIES proc-thread attribute
 * (the kernel builds the lowbox token; no token manipulation, no service).
 *
 * <p>Boundary contract, asserted on this platform by
 * {@code AppContainerBoundaryVerificationTest}: children see the filesystem
 * only where an ACE grants their container SID (system binaries stay
 * reachable via the default ALL APPLICATION PACKAGES RX ACEs); everything
 * else — including the caller's own user profile — is access denied.
 */
public final class AppContainerSandbox {

    private static final Logger log = LoggerFactory.getLogger(AppContainerSandbox.class);

    /** Well-known network capability SIDs (upstream mapping). */
    private static final java.util.Map<String, String> CAPABILITY_SIDS = java.util.Map.of(
            "internetClient", "S-1-15-3-1",
            "internetClientServer", "S-1-15-3-2",
            "privateNetworkClientServer", "S-1-15-3-3");

    /**
     * Child console output encoding: cmd writes in the OEM codepage
     * (GBK on zh-CN), which is not the JVM default (UTF-8 since JEP 400).
     */
    private static final Charset CONSOLE_CHARSET = consoleCharset();

    private static Charset consoleCharset() {
        try {
            int cp = WinApi.Kernel32.INSTANCE.GetOEMCP();
            String name = switch (cp) {
                case 65001 -> "UTF-8";
                case 936 -> "GBK";
                case 950 -> "Big5";
                case 932 -> "Shift_JIS";
                case 949 -> "MS949";
                default -> "windows-" + cp;
            };
            return Charset.forName(name);
        } catch (Throwable t) {
            return Charset.defaultCharset();
        }
    }
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private AppContainerSandbox() {
    }

    /** True when this JVM runs on Windows (the only bridge target for now). */
    public static boolean isSupported() {
        return WinApi.isWindows();
    }

    // ── profile lifecycle ────────────────────────────────────────────────

    /**
     * Create (or reuse) an AppContainer profile; returns its SID string.
     * ERROR_ALREADY_EXISTS is the idempotent-create case — the existing
     * profile's SID is derived instead of failing (upstream semantics).
     */
    public static String ensureProfile(String containerName, String displayName, String description) {
        PointerByReference sidRef = new PointerByReference();
        int hr = WinApi.Userenv.INSTANCE.CreateAppContainerProfile(
                new com.sun.jna.WString(containerName),
                new com.sun.jna.WString(displayName),
                new com.sun.jna.WString(description),
                null, new WinDef.DWORD(0), sidRef);
        if (hr == 0) {
            try {
                String sid = sidToString(sidRef.getValue());
                return sid;
            } finally {
                WinApi.Kernel32.INSTANCE.LocalFree(sidRef.getValue());
            }
        }
        if (hr == WinApi.Userenv.ERROR_ALREADY_EXISTS) {
            String sid = deriveSid(containerName);
            if (sid != null) {
                return sid;
            }
            throw new IllegalStateException(
                    "AppContainer profile '" + containerName + "' exists but SID cannot be derived");
        }
        throw new IllegalStateException(String.format(
                "CreateAppContainerProfile('%s') failed: HRESULT=0x%08x", containerName, hr));
    }

    /** Derive the SID of an existing profile, or null. */
    public static String deriveSid(String containerName) {
        PointerByReference sidRef = new PointerByReference();
        int hr = WinApi.Userenv.INSTANCE.DeriveAppContainerSidFromAppContainerName(
                new com.sun.jna.WString(containerName), sidRef);
        if (hr != 0) {
            return null;
        }
        try {
            return sidToString(sidRef.getValue());
        } finally {
            WinApi.Kernel32.INSTANCE.LocalFree(sidRef.getValue());
        }
    }

    /** Delete a profile by name; returns whether the delete reported success. */
    public static boolean deleteProfile(String containerName) {
        int hr = WinApi.Userenv.INSTANCE.DeleteAppContainerProfile(
                new com.sun.jna.WString(containerName));
        return hr == 0;
    }

    // ── DACL grants ──────────────────────────────────────────────────────

    /**
     * Grant the container SID access to {@code path} via the inbox
     * {@code icacls} tool. Deliberately a subprocess rather than
     * SetEntriesInAclW interop: deny ACEs built through the interop landed
     * with an unusable mask (verified with Get-Acl on this machine), while
     * icacls produces documented, observable ACEs — and for a security
     * boundary the interface whose behaviour can be proven wins.
     * Grants replace prior ACEs for the same SID (idempotent).
     */
    public static void grantPath(String path, String sidString, Access access) {
        String spec = access.icaclsSpec;
        List<String> cmd = new ArrayList<>(List.of("icacls", path));
        for (String action : access.icaclsActions) {
            cmd.add(action);
            cmd.add("*" + sidString + ":" + spec);
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.Charset.defaultCharset());
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException("icacls " + access + " on " + path
                        + " exited " + code + ": " + out.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("icacls interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("icacls failed: " + e.getMessage(), e);
        }
    }

    /** Access levels for {@link #grantPath}. */
    public enum Access {
        /** Full read/write, inheritable — the workspace grant. */
        WORKSPACE_FULL("(OI)(CI)F", "/grant"),
        /** Read + execute, inheritable — extra tool/runtime directories. */
        READ_EXECUTE("(OI)(CI)RX", "/grant"),
        /** Explicit deny-all, inheritable — sensitive paths. */
        DENY_ALL("(OI)(CI)F", "/deny");

        private final String icaclsSpec;
        private final String[] icaclsActions;

        Access(String icaclsSpec, String... icaclsActions) {
            this.icaclsSpec = icaclsSpec;
            this.icaclsActions = icaclsActions;
        }
    }

    /** Capability names → well-known SIDs for the launch attribute. */
    public static List<String> capabilitySids(List<String> names) {
        List<String> sids = new ArrayList<>();
        for (String name : names) {
            String sid = CAPABILITY_SIDS.get(name);
            if (sid != null) {
                sids.add(sid);
            }
        }
        return sids;
    }

    // ── process launch ───────────────────────────────────────────────────

    /**
     * Launch {@code commandLine} (cmd.exe /c spelling) inside the container
     * whose SID is {@code containerSid}, with {@code capabilitySids} as
     * network capabilities. Stdout and stderr are drained through inherited
     * pipes; stdin is closed (batch commands do not read it, mirroring
     * upstream).
     */
    public static Session start(String commandLine, String cwd, String containerSid,
                                List<String> capabilitySids) throws IOException {
        WinApi.Kernel32 kernel = WinApi.Kernel32.INSTANCE;

        Pointer containerSidPtr = stringToSid(containerSid);
        List<Pointer> sidPointers = new ArrayList<>();
        sidPointers.add(containerSidPtr);
        try {
            // SECURITY_CAPABILITIES + SID_AND_ATTRIBUTES array in native memory.
            List<String> caps = capabilitySids == null ? List.of() : capabilitySids;
            WinApi.SECURITY_CAPABILITIES secCap = new WinApi.SECURITY_CAPABILITIES();
            secCap.AppContainerSid = containerSidPtr;
            secCap.Reserved = new WinDef.DWORD(0);
            if (caps.isEmpty()) {
                secCap.Capabilities = null;
                secCap.CapabilityCount = new WinDef.DWORD(0);
            } else {
                WinApi.SID_AND_ATTRIBUTES proto = new WinApi.SID_AND_ATTRIBUTES();
                WinApi.SID_AND_ATTRIBUTES[] arr =
                        (WinApi.SID_AND_ATTRIBUTES[]) proto.toArray(caps.size());
                for (int i = 0; i < caps.size(); i++) {
                    Pointer capSid = stringToSid(caps.get(i));
                    sidPointers.add(capSid);
                    arr[i].Sid = capSid;
                    arr[i].Attributes = new WinDef.DWORD(0x00000004); // SE_GROUP_ENABLED
                    arr[i].write();
                }
                secCap.Capabilities = arr[0].getPointer();
                secCap.CapabilityCount = new WinDef.DWORD(caps.size());
            }
            secCap.write();

            // Attribute list with exactly one entry: security capabilities.
            WinDef.DWORDByReference attrSize = new WinDef.DWORDByReference();
            kernel.InitializeProcThreadAttributeList(null, 1, 0, attrSize);
            Memory attrList = new Memory(attrSize.getValue().longValue());
            if (!kernel.InitializeProcThreadAttributeList(attrList, 1, 0, attrSize)) {
                throw new IOException("InitializeProcThreadAttributeList failed: " + Native.getLastError());
            }
            try {
                if (!kernel.UpdateProcThreadAttribute(attrList, new WinDef.DWORD(0),
                        new WinDef.DWORD(WinApi.Kernel32.PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES),
                        secCap.getPointer(), new WinNT.SIZE_T(secCap.size()), null, null)) {
                    throw new IOException("UpdateProcThreadAttribute failed: " + Native.getLastError());
                }

                // Inherited stdio pipes; stdin closed.
                WinBase.SECURITY_ATTRIBUTES inherit = new WinBase.SECURITY_ATTRIBUTES();
                inherit.dwLength = new WinDef.DWORD(inherit.size());
                inherit.bInheritHandle = true;
                WinNT.HANDLEByReference outRead = new WinNT.HANDLEByReference();
                WinNT.HANDLEByReference outWrite = new WinNT.HANDLEByReference();
                WinNT.HANDLEByReference errRead = new WinNT.HANDLEByReference();
                WinNT.HANDLEByReference errWrite = new WinNT.HANDLEByReference();
                if (!kernel.CreatePipe(outRead, outWrite, inherit, new WinDef.DWORD(0))
                        || !kernel.CreatePipe(errRead, errWrite, inherit, new WinDef.DWORD(0))) {
                    throw new IOException("CreatePipe failed: " + Native.getLastError());
                }

                WinApi.STARTUPINFOEXW si = new WinApi.STARTUPINFOEXW();
                si.StartupInfo.cb = new WinDef.DWORD(si.size());
                si.StartupInfo.dwFlags = WinApi.Kernel32.STARTF_USESTDHANDLES;
                si.StartupInfo.hStdInput = null;
                si.StartupInfo.hStdOutput = outWrite.getValue();
                si.StartupInfo.hStdError = errWrite.getValue();
                si.lpAttributeList = attrList;

                WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
                // CreateProcessW may modify the line and REQUIRES a
                // NUL-terminated buffer — JNA writes char[] at exact length,
                // so the terminator must be added explicitly (without it cmd
                // parses trailing heap garbage into the last argument).
                char[] cmdChars = (commandLine + "\0").toCharArray();
                // Sanitised environment block instead of raw inheritance: a
                // JVM launched from Git Bash carries Git's usr\bin AHEAD of
                // System32, so container children would resolve MSYS
                // coreutils over Windows binaries (UserBinPaths demotes it
                // and adds user bin dirs).
                Memory envBlock = toUnicodeEnvironmentBlock();
                boolean ok = kernel.CreateProcessW(null, cmdChars, null, null, true,
                        new WinDef.DWORD(WinApi.Kernel32.EXTENDED_STARTUPINFO_PRESENT
                                | WinApi.Kernel32.CREATE_UNICODE_ENVIRONMENT
                                | WinApi.Kernel32.CREATE_NO_WINDOW),
                        envBlock, cwd, si, pi);
                int createError = ok ? 0 : Native.getLastError();
                // Parent-side write ends are ours to close either way.
                kernel.CloseHandle(outWrite.getValue());
                kernel.CloseHandle(errWrite.getValue());
                if (!ok) {
                    kernel.CloseHandle(outRead.getValue());
                    kernel.CloseHandle(errRead.getValue());
                    throw new IOException("CreateProcessW failed: " + createError);
                }
                kernel.CloseHandle(pi.hThread);
                return new Session(pi.dwProcessId.intValue(), pi.hProcess,
                        outRead.getValue(), errRead.getValue());
            } finally {
                kernel.DeleteProcThreadAttributeList(attrList);
            }
        } finally {
            for (Pointer p : sidPointers) {
                kernel.LocalFree(p);
            }
        }
    }

    /**
     * Convenience: run a command in the container and return
     * {@code (exitCode, stdout+stderr)}; timeout kills the tree first.
     */
    public static Result run(String commandLine, String cwd, String containerSid,
                             List<String> capabilitySids, int timeoutSeconds) throws IOException {
        int timeout = timeoutSeconds > 0 ? Math.min(timeoutSeconds, 600) : DEFAULT_TIMEOUT_SECONDS;
        try (Session session = start(commandLine, cwd, containerSid, capabilitySids)) {
            Integer code = session.waitFor(timeout);
            if (code == null) {
                session.kill();
                return new Result(-1, "[超时] " + timeout + "s 内未完成，已终止进程树。\n" + session.output());
            }
            return new Result(code, session.output());
        }
    }

    public record Result(int exitCode, String output) {
    }

    // ── session ──────────────────────────────────────────────────────────

    /** A live container child: pipe readers plus native-handle lifecycle. */
    public static final class Session implements AutoCloseable {

        private final int pid;
        private final WinNT.HANDLE process;
        private final StringBuilder output = new StringBuilder();
        private final Thread stdoutPump;
        private final Thread stderrPump;
        private volatile boolean done;

        Session(int pid, WinNT.HANDLE process, WinNT.HANDLE outRead, WinNT.HANDLE errRead) {
            this.pid = pid;
            this.process = process;
            this.stdoutPump = pump(outRead, "sandbox-stdout-" + pid);
            this.stderrPump = pump(errRead, "sandbox-stderr-" + pid);
        }

        public int pid() {
            return pid;
        }

        private Thread pump(WinNT.HANDLE pipe, String name) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[4096];
                IntByReference read = new IntByReference();
                while (!WinApi.Kernel32.INSTANCE.ReadFile(pipe, buf,
                        new WinDef.DWORD(buf.length), read, null) || read.getValue() > 0) {
                    if (read.getValue() <= 0) {
                        break;
                    }
                    synchronized (output) {
                        output.append(new String(buf, 0, read.getValue(), CONSOLE_CHARSET));
                    }
                    read.setValue(0);
                }
                WinApi.Kernel32.INSTANCE.CloseHandle(pipe);
                done = true;
            }, name);
            t.setDaemon(true);
            t.start();
            return t;
        }

        /** Exit code, or null when the timeout elapsed (caller must kill). */
        public Integer waitFor(int timeoutSeconds) {
            WinDef.DWORD wait = WinApi.Kernel32.INSTANCE.WaitForSingleObject(
                    process, new WinDef.DWORD((long) timeoutSeconds * 1000L));
            if (wait.intValue() == WinApi.Kernel32.WAIT_TIMEOUT) {
                return null;
            }
            IntByReference code = new IntByReference();
            WinApi.Kernel32.INSTANCE.GetExitCodeProcess(process, code);
            joinPump(stdoutPump);
            joinPump(stderrPump);
            return code.getValue();
        }

        private static void joinPump(Thread pump) {
            try {
                pump.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public String output() {
            synchronized (output) {
                return output.toString();
            }
        }

        /** Terminate the whole tree: native handle first, Java view fallback. */
        public void kill() {
            try {
                WinApi.Kernel32.INSTANCE.TerminateProcess(process, 1);
            } catch (Throwable ignored) {
            }
            try {
                java.util.Optional<ProcessHandle> ph = ProcessHandle.of(pid);
                ph.ifPresent(h -> {
                    h.descendants().forEach(ProcessHandle::destroyForcibly);
                    h.destroyForcibly();
                });
            } catch (Throwable ignored) {
            }
            try {
                stdoutPump.join(1000);
                stderrPump.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            if (!done) {
                kill();
            }
            WinApi.Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Unicode environment block ({@code NAME=VALUE\0...\0\0}) for
     * {@code CREATE_UNICODE_ENVIRONMENT}: the JVM's environment with PATH
     * run through {@link com.agent.coding.tool.UserBinPaths#applyTo} — user
     * bin dirs prepended and Git's MSYS usr\bin demoted to the tail, so
     * container children resolve Windows-native binaries first.
     */
    private static Memory toUnicodeEnvironmentBlock() {
        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
        com.agent.coding.tool.UserBinPaths.applyTo(env);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : env.entrySet()) {
            sb.append(entry.getKey()).append('=')
                    .append(entry.getValue() == null ? "" : entry.getValue())
                    .append('\0');
        }
        sb.append('\0');
        char[] chars = sb.toString().toCharArray();
        // Fresh Memory is zero-filled, so the trailing L'\0' plus the block's
        // own terminator form the required double-null ending.
        Memory block = new Memory((chars.length + 1) * 2L);
        block.write(0, chars, 0, chars.length);
        return block;
    }

    private static Pointer stringToSid(String sidString) {
        PointerByReference ref = new PointerByReference();
        if (!WinApi.SidConversion.INSTANCE.ConvertStringSidToSidW(sidString, ref)) {
            throw new IllegalStateException("ConvertStringSidToSidW failed for " + sidString);
        }
        return ref.getValue();
    }

    private static String sidToString(Pointer sid) {
        if (sid == null) {
            return null;
        }
        PointerByReference stringRef = new PointerByReference();
        if (!WinApi.SidConversion.INSTANCE.ConvertSidToStringSidW(sid, stringRef)) {
            throw new IllegalStateException("ConvertSidToStringSidW failed");
        }
        Pointer p = stringRef.getValue();
        try {
            return p.getWideString(0);
        } finally {
            WinApi.Kernel32.INSTANCE.LocalFree(p);
        }
    }
}
