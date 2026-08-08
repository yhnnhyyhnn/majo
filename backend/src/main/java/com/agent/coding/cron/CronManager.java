package com.agent.coding.cron;

import com.agent.coding.inbox.InboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Cron job manager: schedules jobs, tracks per-job state and history, and
 * exposes the CRUD/control operations used by the REST API. Ported from
 * qwenpaw app/crons/manager.py.
 *
 * <p>Storage lives under the agent workspace ({@code jobs.json} +
 * {@code jobs_history/}), matching qwenpaw's per-workspace repository. The
 * scheduler is a Spring {@link ThreadPoolTaskScheduler}: cron jobs use
 * {@link CronTrigger} (6-field, seconds prepended from the normalized 5-field
 * expression), "once" jobs are scheduled at their run_at with optional daily
 * repeat. Created by {@code CronController.managerFor} (one per agent), not
 * managed by the Spring context.
 */
public class CronManager {

    private static final Logger log = LoggerFactory.getLogger(CronManager.class);
    private static final int HISTORY_LIMIT = 50;

    private final ThreadPoolTaskScheduler scheduler;
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> states = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> historyCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> concurrencyCount = new ConcurrentHashMap<>();
    private final CronJobRepository repo;
    private final CronExecutor executor;
    private final String agentId;
    private volatile boolean started = false;

    public CronManager(CronJobRepository repo, CronExecutor executor, String agentId) {
        this.repo = repo;
        this.executor = executor;
        this.agentId = agentId == null || agentId.isBlank() ? "default" : agentId;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(4);
        this.scheduler.setThreadNamePrefix("cron-");
        this.scheduler.initialize();
    }

    public CronJobRepository repo() {
        return repo;
    }

    /** Load jobs from disk, register their schedules, prune orphan history. */
    public synchronized void start() {
        if (started) return;
        started = true;
        List<Map<String, Object>> jobs = repo.listJobs();
        Set<String> validIds = new java.util.HashSet<>();
        for (Map<String, Object> job : jobs) {
            if (job.get("id") != null) validIds.add(String.valueOf(job.get("id")));
        }
        repo.pruneOrphanHistory(validIds);
        for (Map<String, Object> job : jobs) {
            try {
                registerOrUpdate(job);
            } catch (Exception e) {
                log.warn("Skipping invalid cron job during startup: job_id={} error={}",
                        job.get("id"), e.getMessage());
                // Auto-disable invalid enabled jobs (qwenpaw behavior)
                if (Boolean.TRUE.equals(job.get("enabled"))) {
                    job.put("enabled", false);
                    repo.upsertJob(job);
                    log.warn("Auto-disabled invalid cron job: job_id={}", job.get("id"));
                }
            }
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    // ------------------------------------------------------------------
    // Read operations
    // ------------------------------------------------------------------

    public List<Map<String, Object>> listJobs() {
        return repo.listJobs();
    }

    public Map<String, Object> getJob(String jobId) {
        return repo.getJob(jobId);
    }

    public Map<String, Object> getState(String jobId) {
        return states.getOrDefault(jobId, new LinkedHashMap<>());
    }

    public List<Map<String, Object>> getHistory(String jobId) {
        List<Map<String, Object>> cached = historyCache.get(jobId);
        if (cached == null) {
            cached = repo.getHistory(jobId);
            historyCache.put(jobId, cached);
        }
        return cached;
    }

    // ------------------------------------------------------------------
    // Write / control operations
    // ------------------------------------------------------------------

    public synchronized void createOrReplaceJob(Map<String, Object> spec) {
        repo.upsertJob(spec);
        registerOrUpdate(spec);
    }

    public synchronized boolean deleteJob(String jobId) {
        ScheduledFuture<?> f = scheduled.remove(jobId);
        if (f != null) f.cancel(false);
        states.remove(jobId);
        historyCache.remove(jobId);
        repo.deleteHistory(jobId);
        concurrencyCount.remove(jobId);
        return repo.deleteJob(jobId);
    }

    public synchronized void pauseJob(String jobId) {
        ScheduledFuture<?> f = scheduled.get(jobId);
        if (f != null) f.cancel(false);
        // Mark state as paused by clearing next_run_at
        Map<String, Object> st = states.computeIfAbsent(jobId, k -> new LinkedHashMap<>());
        st.put("next_run_at", null);
    }

    public synchronized void resumeJob(String jobId) {
        Map<String, Object> job = repo.getJob(jobId);
        if (job != null) {
            registerOrUpdate(job);
        }
    }

    public void runJob(String jobId) {
        Map<String, Object> job = repo.getJob(jobId);
        if (job == null) {
            throw new java.util.NoSuchElementException("Job not found: " + jobId);
        }
        scheduler.execute(() -> executeOnce(job, "manual"));
    }

    // ------------------------------------------------------------------
    // Scheduling internals
    // ------------------------------------------------------------------

    private synchronized void registerOrUpdate(Map<String, Object> job) {
        String jobId = String.valueOf(job.get("id"));
        ScheduledFuture<?> existing = scheduled.remove(jobId);
        if (existing != null) existing.cancel(false);

        Map<String, Object> schedule = CronModels.schedule(job);
        String type = String.valueOf(schedule.getOrDefault("type", "cron"));
        String timezone = String.valueOf(schedule.getOrDefault("timezone", "UTC"));
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }
        boolean enabled = !Boolean.FALSE.equals(job.get("enabled"));

        if ("once".equals(type)) {
            Long runAt = CronModels.parseInstant(String.valueOf(schedule.get("run_at")));
            if (runAt == null) {
                throw new IllegalArgumentException("schedule.type is once but run_at is missing");
            }
            Object repeatDays = schedule.get("repeat_every_days");
            if (enabled && repeatDays != null && repeatDays instanceof Number n && n.intValue() > 0) {
                scheduleRepeatingOnce(job, runAt, n.intValue(), zone);
            } else if (enabled) {
                scheduleAt(jobId, runAt, zone);
            }
            Map<String, Object> st = states.computeIfAbsent(jobId, k -> new LinkedHashMap<>());
            st.put("next_run_at", CronModels.formatInstant(runAt, timezone));
        } else {
            String cron = String.valueOf(schedule.getOrDefault("cron", "0 9 * * *"));
            // Spring CronTrigger expects 6 fields (second minute hour dom month dow);
            // qwenpaw normalizes to 5 fields, so prepend seconds.
            String cron6 = "0 " + cron;
            if (enabled) {
                ScheduledFuture<?> f = scheduler.schedule(
                        () -> onScheduled(jobId),
                        new CronTrigger(cron6, zone));
                scheduled.put(jobId, f);
                try {
                    java.time.Instant next = nextCronRun(cron6, zone);
                    Map<String, Object> st = states.computeIfAbsent(jobId, k -> new LinkedHashMap<>());
                    st.put("next_run_at", next == null ? null
                            : CronModels.formatInstant(next.toEpochMilli(), timezone));
                } catch (Exception e) {
                    log.debug("cron: could not compute next_run for {}", jobId);
                }
            } else {
                Map<String, Object> st = states.computeIfAbsent(jobId, k -> new LinkedHashMap<>());
                st.put("next_run_at", null);
            }
        }
    }

    private java.time.Instant nextCronRun(String cron6, ZoneId zone) {
        var trigger = new CronTrigger(cron6, zone);
        var ctx = new org.springframework.scheduling.support.SimpleTriggerContext();
        return trigger.nextExecution(ctx);
    }

    private void scheduleAt(String jobId, long runAtMillis, ZoneId zone) {
        long delay = Math.max(0, runAtMillis - System.currentTimeMillis());
        ScheduledFuture<?> f = scheduler.schedule(() -> onScheduled(jobId),
                Instant.ofEpochMilli(System.currentTimeMillis() + delay));
        scheduled.put(jobId, f);
    }

    /** Schedule a "once" job that repeats every N days until end condition. */
    private void scheduleRepeatingOnce(Map<String, Object> job, long firstRunAt, int everyDays, ZoneId zone) {
        String jobId = String.valueOf(job.get("id"));
        Map<String, Object> schedule = CronModels.schedule(job);
        String endType = String.valueOf(schedule.getOrDefault("repeat_end_type", "never"));
        final Long endAt;
        if ("until".equals(endType)) {
            endAt = CronModels.parseInstant(String.valueOf(schedule.get("repeat_until")));
        } else if ("count".equals(endType)) {
            Object countObj = schedule.get("repeat_count");
            int count = countObj instanceof Number n ? n.intValue() : 1;
            endAt = firstRunAt + (long) everyDays * 86400_000L * Math.max(count - 1, 0);
        } else {
            endAt = null;
        }
        final int every = everyDays;

        final long[] runAt = {firstRunAt};
        scheduleOneOccurrence(jobId, Math.max(0, firstRunAt - System.currentTimeMillis()), () -> {
            if (endAt == null || System.currentTimeMillis() <= endAt) {
                onScheduled(jobId);
            }
            long next = runAt[0] + (long) every * 86400_000L;
            runAt[0] = next;
            if (endAt == null || next <= endAt) {
                scheduleRepeatingOnce(job, next, every, zone);
            }
        });
    }

    private void scheduleOneOccurrence(String jobId, long delayMillis, Runnable onFire) {
        ScheduledFuture<?> f = scheduler.schedule(
                () -> onFire.run(),
                Instant.ofEpochMilli(System.currentTimeMillis() + Math.max(0, delayMillis)));
        scheduled.put(jobId, f);
    }

    private void onScheduled(String jobId) {
        Map<String, Object> job = repo.getJob(jobId);
        if (job == null) return;
        try {
            executeOnce(job, "scheduled");
        } finally {
            // refresh next_run
            Map<String, Object> st = states.computeIfAbsent(jobId, k -> new LinkedHashMap<>());
            try {
                Map<String, Object> schedule = CronModels.schedule(job);
                String cron = String.valueOf(schedule.getOrDefault("cron", "0 9 * * *"));
                String timezone = String.valueOf(schedule.getOrDefault("timezone", "UTC"));
                ZoneId zone;
                try {
                    zone = ZoneId.of(timezone);
                } catch (Exception e) {
                    zone = ZoneId.of("UTC");
                }
                var trigger = new CronTrigger("0 " + cron, zone);
                java.time.Instant next = trigger.nextExecution(new org.springframework.scheduling.support.SimpleTriggerContext());
                st.put("next_run_at", next == null ? null : CronModels.formatInstant(next.toEpochMilli(), timezone));
            } catch (Exception e) {
                st.put("next_run_at", null);
            }
        }
    }

    /** Execute the job once, record state + history, append inbox events. */
    private void executeOnce(Map<String, Object> job, String trigger) {
        String jobId = String.valueOf(job.get("id"));
        String jobName = String.valueOf(job.get("name"));
        Map<String, Object> st = states.computeIfAbsent(jobId, k -> new LinkedHashMap<>());
        st.put("last_status", "running");
        st.put("last_error", null);

        CronExecutor.ExecutionResult result = null;
        boolean succeeded = false;
        boolean deliveryFailed = false;
        try {
            result = executor.execute(job, agentId);
            succeeded = true;
            deliveryFailed = "failed".equals(result.deliveryStatus());
            if (deliveryFailed) {
                st.put("last_status", "error");
                st.put("last_error", "delivery failed: " + (result.deliveryError() == null ? "" : result.deliveryError()));
            } else {
                st.put("last_status", "success");
                st.put("last_error", null);
            }
        } catch (Exception e) {
            st.put("last_status", "error");
            st.put("last_error", String.valueOf(e.getMessage()));
            log.warn("cron _execute_once: job_id={} status=error error={}", jobId, e.getMessage());
        } finally {
            String tz = String.valueOf(CronModels.schedule(job).getOrDefault("timezone", "UTC"));
            String runAt = CronModels.formatInstant(System.currentTimeMillis(), tz);
            st.put("last_run_at", runAt);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("run_at", runAt);
            record.put("status", st.get("last_status"));
            record.put("error", st.get("last_error"));
            record.put("trigger", trigger);
            List<Map<String, Object>> records = repo.appendHistory(jobId, record);
            historyCache.put(jobId, records);

            // Inbox events (manager._execute_once tail)
            boolean saveToInbox = !Boolean.FALSE.equals(job.get("save_result_to_inbox"));
            if (succeeded && deliveryFailed) {
                appendInboxEvent(job, trigger, result, "cron_delivery_failed_fallback", "error",
                        "error", "Cron result not delivered: " + jobName,
                        "Task executed successfully, but channel delivery failed.");
            } else if (succeeded && saveToInbox) {
                String body = "text".equals(job.get("task_type"))
                        ? String.valueOf(job.get("text")).trim()
                        : "Agent cron task finished successfully.";
                appendInboxEvent(job, trigger, result, "cron_result", "success",
                        "info", "Cron result: " + jobName, body);
            }
        }
    }

    private void appendInboxEvent(Map<String, Object> job, String trigger,
                                  CronExecutor.ExecutionResult result, String eventType,
                                  String status, String severity, String title, String body) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("job_id", job.get("id"));
            payload.put("job_name", job.get("name"));
            payload.put("task_type", job.get("task_type"));
            payload.put("trigger", trigger);
            if (result != null && result.runId() != null) payload.put("run_id", result.runId());
            if (result != null && result.deliveryError() != null) {
                payload.put("delivery_error", result.deliveryError());
            }
            payload.put("save_result_to_inbox", job.get("save_result_to_inbox"));
            inboxAppender().appendEvent(agentId, "cron", String.valueOf(job.get("id")),
                    eventType, status, title, body, severity, payload);
        } catch (Exception e) {
            log.warn("failed to append cron inbox event for job {}", job.get("id"), e);
        }
    }

    private InboxStore inboxAppender() {
        return executor.inboxStore();
    }
}
