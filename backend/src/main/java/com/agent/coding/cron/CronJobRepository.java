package com.agent.coding.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File-backed cron job repository, ported from qwenpaw
 * app/crons/repo/json_repo.py. Jobs live in {@code jobs.json} as
 * {@code {"version": 2, "jobs": [...]}}; per-job execution history lives in
 * {@code jobs_history/{urlencoded_id}.json} as a JSON list (newest first,
 * capped at 50). All writes are atomic (tmp + move) under an advisory lock.
 */
public class CronJobRepository {

    private static final Logger log = LoggerFactory.getLogger(CronJobRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HISTORY_LIMIT = 50;

    private final Path jobsPath;
    private final Path historyDir;

    public CronJobRepository(Path workspaceDir) {
        this.jobsPath = workspaceDir.resolve("jobs.json");
        this.historyDir = workspaceDir.resolve("jobs_history");
    }

    public Path jobsPath() {
        return jobsPath;
    }

    // ------------------------------------------------------------------
    // Jobs file I/O
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> loadJobs() {
        if (!Files.isRegularFile(jobsPath)) {
            return new ArrayList<>();
        }
        try (FileChannel channel = FileChannel.open(lockPath(jobsPath),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            String text = Files.readString(jobsPath, StandardCharsets.UTF_8);
            Object parsed = MAPPER.readValue(text, Object.class);
            if (!(parsed instanceof Map<?, ?> m)) return new ArrayList<>();
            Object jobs = m.get("jobs");
            if (!(jobs instanceof List<?> list)) return new ArrayList<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> jm) {
                    result.add(toMap(jm));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load cron jobs from {}: {}", jobsPath, e.getMessage());
            return new ArrayList<>();
        }
    }

    private synchronized void saveJobs(List<Map<String, Object>> jobs) {
        try {
            Files.createDirectories(jobsPath.getParent());
        } catch (IOException e) {
            log.warn("Cannot create jobs dir for {}", jobsPath, e);
        }
        try (FileChannel channel = FileChannel.open(lockPath(jobsPath),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", 2);
            payload.put("jobs", jobs);
            Path temp = jobsPath.resolveSibling(jobsPath.getFileName() + ".tmp");
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, jobsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, jobsPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Failed to write cron jobs to {}", jobsPath, e);
        }
    }

    private static Path lockPath(Path jsonPath) {
        return jsonPath.getParent().resolve("." + jsonPath.getFileName() + ".lock");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Map<?, ?> m) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) row.put(String.valueOf(e.getKey()), e.getValue());
        }
        return row;
    }

    // ------------------------------------------------------------------
    // Job CRUD
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listJobs() {
        return loadJobs();
    }

    public Map<String, Object> getJob(String jobId) {
        for (Map<String, Object> job : loadJobs()) {
            if (jobId.equals(job.get("id"))) return job;
        }
        return null;
    }

    public synchronized void upsertJob(Map<String, Object> spec) {
        List<Map<String, Object>> jobs = loadJobs();
        boolean replaced = false;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).get("id") != null && jobs.get(i).get("id").equals(spec.get("id"))) {
                jobs.set(i, spec);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            jobs.add(spec);
        }
        saveJobs(jobs);
    }

    public synchronized boolean deleteJob(String jobId) {
        List<Map<String, Object>> jobs = loadJobs();
        int before = jobs.size();
        jobs.removeIf(j -> jobId.equals(j.get("id")));
        if (jobs.size() == before) return false;
        saveJobs(jobs);
        return true;
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    private Path historyFilePath(String jobId) {
        String encoded = URLEncoder.encode(jobId, StandardCharsets.UTF_8);
        return historyDir.resolve(encoded + ".json");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readHistory(String jobId) {
        Path path = historyFilePath(jobId);
        if (!Files.isRegularFile(path)) return new ArrayList<>();
        try {
            Object parsed = MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8), Object.class);
            if (!(parsed instanceof List<?> list)) return new ArrayList<>();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) result.add(toMap(m));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to read cron history {}: {}", path, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeHistory(String jobId, List<Map<String, Object>> records) {
        Path path = historyFilePath(jobId);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            log.warn("Cannot create history dir for {}", path, e);
        }
        try (FileChannel channel = FileChannel.open(lockPath(path),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Failed to write cron history {}: {}", path, e.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> getHistory(String jobId) {
        return readHistory(jobId);
    }

    public synchronized List<Map<String, Object>> appendHistory(String jobId, Map<String, Object> record) {
        List<Map<String, Object>> records = readHistory(jobId);
        records.add(0, record);
        while (records.size() > HISTORY_LIMIT) {
            records.remove(records.size() - 1);
        }
        writeHistory(jobId, records);
        return records;
    }

    public synchronized void deleteHistory(String jobId) {
        Path path = historyFilePath(jobId);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete cron history {}", path, e);
        }
    }

    /** Remove history files that no longer belong to any known job. */
    public synchronized void pruneOrphanHistory(Set<String> validJobIds) {
        if (!Files.isDirectory(historyDir)) return;
        try (var stream = Files.list(historyDir)) {
            for (Path p : stream.filter(x -> x.getFileName().toString().endsWith(".json")).toList()) {
                String encoded = p.getFileName().toString().substring(0,
                        p.getFileName().toString().length() - 5);
                if (!validJobIds.contains(decode(encoded))) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to prune cron history in {}", historyDir, e);
        }
    }

    private static String decode(String encoded) {
        try {
            return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encoded;
        }
    }
}
