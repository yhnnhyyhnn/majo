package com.agent.coding.backup;

import com.agent.coding.agent.AgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application-owned async backup creation jobs, mirroring qwenpaw's
 * backups/jobs model: at most one active job, a serializable snapshot for
 * polling/reconnect, and cooperative cancellation.
 */
public class BackupJobManager {

    public enum Status { PENDING, RUNNING, CANCEL_REQUESTED, COMPLETED, FAILED, CANCELLED }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BackupJobManager INSTANCE = new BackupJobManager();

    public static BackupJobManager get() {
        return INSTANCE;
    }

    /** Serializable current state of a backup creation job. */
    public static final class Snapshot {
        public String jobId;
        public String backupId = "";
        public String status = Status.PENDING.name().toLowerCase();
        public String phase = "preparing";
        public int percent;
        public String currentAgent;
        public int agentIndex;
        public int totalAgents;
        public Map<String, Object> result;
        public String error;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("job_id", jobId);
            m.put("backup_id", backupId);
            m.put("status", status);
            m.put("phase", phase);
            m.put("percent", percent);
            m.put("current_agent", currentAgent);
            m.put("agent_index", agentIndex);
            m.put("total_agents", totalAgents);
            m.put("result", result);
            m.put("error", error);
            return m;
        }
    }

    static final class Job {
        final Snapshot snapshot = new Snapshot();
        final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        volatile Thread worker;
    }

    private Job active; // at most one running job, matching qwenpaw
    private final ConcurrentHashMap<String, Job> finished = new ConcurrentHashMap<>();

    /** Start a backup creation job; returns the initial snapshot. */
    public synchronized Snapshot startJob(BackupMeta.Scope scope, List<String> agentIds,
                                          String name, String description) throws IllegalStateException {
        if (active != null && !isTerminal(active.snapshot.status)) {
            throw new IllegalStateException("A backup job is already running");
        }
        Job job = new Job();
        job.snapshot.jobId = "bkjob-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        job.snapshot.status = Status.RUNNING.name().toLowerCase();
        if (scope.include_agents) {
            int total = 0;
            for (String aid : agentIds) {
                if (AgentStore.getProfile(aid) != null && !AgentStore.getProfile(aid).isEmpty()) total++;
            }
            job.snapshot.totalAgents = total;
        }
        active = job;
        Thread worker = new Thread(() -> runJob(job, scope, agentIds, name, description), "backup-job");
        job.worker = worker;
        worker.start();
        return snapshot(job);
    }

    private void runJob(Job job, BackupMeta.Scope scope, List<String> agentIds,
                        String name, String description) {
        try {
            BackupCreator.create(scope, agentIds, name, description, event -> {
                applyEvent(job, event);
            }, job.cancelRequested::get);
        } catch (BackupCancelledException e) {
            finish(job, Status.CANCELLED, null, "Backup cancelled");
        } catch (Exception e) {
            log.error("Backup job failed", e);
            finish(job, Status.FAILED, null, e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }
        if (!Status.CANCELLED.name().toLowerCase().equals(job.snapshot.status)) {
            job.snapshot.percent = 100;
            finish(job, Status.COMPLETED, job.snapshot.result, null);
        }
    }

    private void applyEvent(Job job, Map<String, Object> event) {
        String type = String.valueOf(event.get("type"));
        Object percent = event.get("percent");
        switch (type) {
            case "start" -> {
                job.snapshot.phase = "agents";
                if (percent instanceof Number n) job.snapshot.percent = n.intValue();
            }
            case "agent" -> {
                job.snapshot.currentAgent = String.valueOf(event.get("agent_id"));
                if (event.get("index") instanceof Number n) job.snapshot.agentIndex = n.intValue();
                if (percent instanceof Number n) job.snapshot.percent = n.intValue();
            }
            case "saving" -> {
                job.snapshot.phase = "finalizing";
                job.snapshot.currentAgent = null;
                if (percent instanceof Number n) job.snapshot.percent = n.intValue();
            }
            case "done" -> {
                Object meta = event.get("meta");
                if (meta != null) {
                    job.snapshot.result = MAPPER.convertValue(meta, Map.class);
                    job.snapshot.backupId = String.valueOf(
                            job.snapshot.result != null ? job.snapshot.result.get("id") : "");
                }
                if (percent instanceof Number n) job.snapshot.percent = n.intValue();
            }
            default -> { }
        }
    }

    private void finish(Job job, Status status, Map<String, Object> result, String error) {
        job.snapshot.status = status.name().toLowerCase();
        if (result != null) job.snapshot.result = result;
        if (error != null) job.snapshot.error = error;
        finished.put(job.snapshot.jobId, job);
        if (active == job) {
            active = null;
        }
    }

    /** Latest snapshot of a job (running or finished history). */
    public Snapshot getJob(String jobId) {
        if (active != null && active.snapshot.jobId.equals(jobId)) {
            return snapshot(active);
        }
        Job done = finished.get(jobId);
        return done == null ? null : snapshot(done);
    }

    public Snapshot getActiveJob() {
        return active == null ? null : snapshot(active);
    }

    public synchronized Snapshot cancelJob(String jobId) {
        Job job = active != null && active.snapshot.jobId.equals(jobId)
                ? active : finished.get(jobId);
        if (job == null || isTerminal(job.snapshot.status)) {
            return job == null ? null : snapshot(job);
        }
        job.cancelRequested.set(true);
        job.snapshot.status = Status.CANCEL_REQUESTED.name().toLowerCase();
        return snapshot(job);
    }

    public boolean isCancelRequested(String jobId) {
        Job job = active;
        return job != null && job.snapshot.jobId.equals(jobId) && job.cancelRequested.get();
    }

    private static boolean isTerminal(String status) {
        return Status.COMPLETED.name().toLowerCase().equals(status)
                || Status.FAILED.name().toLowerCase().equals(status)
                || Status.CANCELLED.name().toLowerCase().equals(status);
    }

    private static Snapshot snapshot(Job job) {
        Snapshot s = new Snapshot();
        s.jobId = job.snapshot.jobId;
        s.backupId = job.snapshot.backupId;
        s.status = job.snapshot.status;
        s.phase = job.snapshot.phase;
        s.percent = job.snapshot.percent;
        s.currentAgent = job.snapshot.currentAgent;
        s.agentIndex = job.snapshot.agentIndex;
        s.totalAgents = job.snapshot.totalAgents;
        s.result = job.snapshot.result;
        s.error = job.snapshot.error;
        return s;
    }

    /** Cooperative cancellation signal raised inside BackupCreator's sink. */
    public static final class BackupCancelledException extends RuntimeException {
        public BackupCancelledException() {
            super("Backup cancelled");
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BackupJobManager.class);
}
