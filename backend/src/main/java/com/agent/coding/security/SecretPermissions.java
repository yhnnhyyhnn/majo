package com.agent.coding.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Read-time permission hardening for credential files. Ported from QwenPaw
 * master-key read hardening (#7699): when a credential file carries
 * group/other permission bits, strip them without making stricter owner
 * permissions (e.g. 0o400) more permissive.
 *
 * <p>Hardening failures are logged and swallowed — treating a permission
 * check failure as an error would break the read path; the file's content
 * is still valid.
 */
public final class SecretPermissions {

    private static final Logger log = LoggerFactory.getLogger(SecretPermissions.class);

    private SecretPermissions() {}

    /**
     * Strip group/other bits from a credential file on read (POSIX only).
     *
     * @return true when the file is safe to use (or hardening was skipped
     *         on a non-POSIX filesystem), false when verification failed
     */
    public static boolean hardenOnRead(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return true;
        }
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return true;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (perms.contains(PosixFilePermission.GROUP_READ)
                    || perms.contains(PosixFilePermission.GROUP_WRITE)
                    || perms.contains(PosixFilePermission.GROUP_EXECUTE)
                    || perms.contains(PosixFilePermission.OTHERS_READ)
                    || perms.contains(PosixFilePermission.OTHERS_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                // Keep only the owner bits the file already grants.
                Set<PosixFilePermission> corrected = ownerOnlyOf(perms);
                Files.setPosixFilePermissions(file, corrected);
                log.warn("[secrets] credential file {} had insecure permissions; corrected to {}",
                        file, PosixFilePermissions.toString(corrected));
            }
            return true;
        } catch (Exception e) {
            // Keep using the existing file: failing the read here would make
            // callers treat a valid credential as missing.
            log.warn("[secrets] could not verify/correct permissions on {}: {}",
                    file, e.getMessage());
            return false;
        }
    }

    private static Set<PosixFilePermission> ownerOnlyOf(Set<PosixFilePermission> perms) {
        Set<PosixFilePermission> out = new java.util.LinkedHashSet<>();
        for (PosixFilePermission p : perms) {
            String name = p.name();
            if (name.startsWith("OWNER_")) {
                out.add(p);
            }
        }
        return out;
    }
}
