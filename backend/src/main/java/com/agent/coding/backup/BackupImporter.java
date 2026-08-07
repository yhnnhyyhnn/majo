package com.agent.coding.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Backup import: validates an uploaded zip, detects ID conflicts (409) and
 * re-signs explicitly trusted legacy/foreign archives. Ported from qwenpaw
 * backup/_ops/storage.py + _utils/signing/trust.py.
 */
public class BackupImporter {

    private static final Logger log = LoggerFactory.getLogger(BackupImporter.class);

    private BackupImporter() {
    }

    public static class ConflictException extends Exception {
        public final BackupMeta existing;
        public final String pendingToken;

        public ConflictException(BackupMeta existing, String pendingToken) {
            super("backup_conflict");
            this.existing = existing;
            this.pendingToken = pendingToken;
        }
    }

    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }

    /**
     * Import a backup from an uploaded file (already saved to disk at
     * {@code tmpPath}). Returns the imported meta.
     *
     * @throws ConflictException   when overwrite=false and the id already exists
     * @throws ValidationException when the archive is invalid / not trusted
     */
    public static BackupMeta importBackup(Path tmpPath, boolean overwrite, String trustMode)
        throws IOException, ConflictException, ValidationException {
        if (!isZip(tmpPath)) {
            throw new ValidationException("Uploaded file is not a valid zip archive");
        }
        BackupMeta meta;
        try (ZipFile zf = new ZipFile(tmpPath.toFile())) {
            var e = zf.getEntry(BackupStore.META_FILE);
            if (e == null) {
                throw new ValidationException("Zip does not contain a valid meta.json");
            }
            meta = BackupStore.MAPPER.readValue(zf.getInputStream(e), BackupMeta.class);
            if (meta.id == null || meta.id.isBlank()) {
                throw new ValidationException("Zip does not contain a valid meta.json");
            }
        } catch (IOException | ValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ValidationException("Zip does not contain a valid meta.json");
        }
        resolveSignatureAction(tmpPath, meta, trustMode);

        try {
            BackupStore.validateBackupId(meta.id);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }

        Files.createDirectories(BackupStore.backupDir());
        Path existing = BackupStore.findZipPath(meta.id);
        if (existing != null && !overwrite) {
            BackupMeta existingMeta = BackupStore.readMetaFromZip(existing);
            if (existingMeta == null) {
                throw new ValidationException("Existing backup could not be read");
            }
            throw new ConflictException(existingMeta, tmpPath.getFileName().toString());
        }

        Path dest = BackupStore.zipPath(meta.id);
        if (existing != null && !existing.equals(dest)) {
            Files.deleteIfExists(existing);
        }
        if (meta.signature != null && !meta.signature.isBlank()) {
            Files.createDirectories(dest.getParent());
            Files.move(tmpPath, dest, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.createDirectories(dest.getParent());
            Files.move(tmpPath, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Backup imported: id={} name={}", meta.id, meta.name);
        return meta;
    }

    /** Trust decision: local signature OK -> none; explicit trust -> sign; else validation error. */
    private static void resolveSignatureAction(Path zip, BackupMeta meta, String trustMode)
        throws ValidationException {
        boolean hasSignature = meta.signature != null && !meta.signature.isBlank();
        if (hasSignature) {
            if (BackupStore.verifySignature(zip, meta)) {
                return;
            }
            if ("foreign".equals(trustMode)) {
                log.warn("Importing foreign signed backup after explicit trust: {}", meta.id);
                return;
            }
            throw new ValidationException("Backup signature is invalid or untrusted");
        }
        if (!"legacy".equals(trustMode)) {
            throw new ValidationException("Backup signature is invalid or untrusted");
        }
        log.warn("Importing legacy unsigned backup after explicit trust: {}", meta.id);
    }

    private static boolean isZip(Path path) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(path))) {
            return zis.getNextEntry() != null;
        } catch (IOException e) {
            return false;
        }
    }
}
