package com.exampleco.crm.service.quartz;

import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Backs the admin-only Scheduled Jobs control UI (QuartzJobAdminController) — every
// blocking org.quartz.Scheduler call lives here so it's directly unit-testable with a mocked
// Scheduler, with the controller staying a thin Mono.fromCallable(...) wrapper per the reactive-
// shell rule. Generic over every job/trigger currently registered (scheduler.getJobKeys(
// GroupMatcher.anyGroup())) rather than hardcoded to NotifyFailedTicketsJob, so a future second
// job needs no change here.
//
// Per this codebase's exception-handling convention ("only handle exception in
// Controller/Job/Consumer/View/js, don't handle in Service"), every checked SchedulerException
// here is declared, never caught — QuartzJobAdminController's own @ExceptionHandler owns turning
// it into a clean response.
@Component
public class QuartzJobService {

    private final Scheduler scheduler;

    public QuartzJobService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public record JobSummary(
            String jobName, String jobGroup,
            String triggerName, String triggerGroup,
            LocalDateTime startTime, LocalDateTime nextFireTime,
            String type, String status, String misfireInstruction, String schedule) {
    }

    public record TriggerDetail(
            String jobName, String jobGroup,
            String triggerName, String triggerGroup,
            String description, int priority,
            LocalDateTime startTime, LocalDateTime endTime,
            String cronExpression, String status, String misfireInstruction) {
    }

    public List<JobSummary> listJobs() throws SchedulerException {
        List<JobSummary> summaries = new ArrayList<>();
        for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            for (Trigger trigger : scheduler.getTriggersOfJob(jobKey)) {
                summaries.add(toSummary(jobKey, trigger));
            }
        }
        return summaries;
    }

    // Returns null if no such trigger exists — mirrors this codebase's existing
    // findById().orElse(null) style, so the controller can turn that into a plain 404.
    public TriggerDetail getTriggerDetail(String triggerGroup, String triggerName) throws SchedulerException {
        TriggerKey triggerKey = TriggerKey.triggerKey(triggerName, triggerGroup);
        Trigger trigger = scheduler.getTrigger(triggerKey);
        if (trigger == null) {
            return null;
        }
        Trigger.TriggerState state = scheduler.getTriggerState(triggerKey);
        return new TriggerDetail(
                trigger.getJobKey().getName(), trigger.getJobKey().getGroup(),
                triggerKey.getName(), triggerKey.getGroup(),
                trigger.getDescription(), trigger.getPriority(),
                toLocalDateTime(trigger.getStartTime()), toLocalDateTime(trigger.getEndTime()),
                cronExpressionOf(trigger), state.name(), misfireLabel(trigger));
    }

    public void pauseAll() throws SchedulerException {
        scheduler.pauseAll();
    }

    public void resumeAll() throws SchedulerException {
        scheduler.resumeAll();
    }

    public boolean pauseTrigger(String triggerGroup, String triggerName) throws SchedulerException {
        TriggerKey triggerKey = TriggerKey.triggerKey(triggerName, triggerGroup);
        if (!scheduler.checkExists(triggerKey)) {
            return false;
        }
        scheduler.pauseTrigger(triggerKey);
        return true;
    }

    public boolean resumeTrigger(String triggerGroup, String triggerName) throws SchedulerException {
        TriggerKey triggerKey = TriggerKey.triggerKey(triggerName, triggerGroup);
        if (!scheduler.checkExists(triggerKey)) {
            return false;
        }
        scheduler.resumeTrigger(triggerKey);
        return true;
    }

    // Fires the job immediately, independent of (and without disturbing) its own trigger's
    // schedule — scheduler.triggerJob(...), not a reschedule.
    public boolean triggerNow(String triggerGroup, String triggerName) throws SchedulerException {
        TriggerKey triggerKey = TriggerKey.triggerKey(triggerName, triggerGroup);
        Trigger trigger = scheduler.getTrigger(triggerKey);
        if (trigger == null) {
            return false;
        }
        scheduler.triggerJob(trigger.getJobKey());
        return true;
    }

    // Validates the cron expression before touching the scheduler at all — Quartz's own
    // CronScheduleBuilder throws an unchecked RuntimeException on a bad expression, which would
    // otherwise surface as an opaque 500. Returns false (not an exception) when the trigger
    // itself doesn't exist, same "not found" convention as the other methods above.
    public boolean reschedule(String triggerGroup, String triggerName, String cronExpression) throws SchedulerException {
        String trimmed = cronExpression == null ? "" : cronExpression.trim();
        if (trimmed.isEmpty() || !CronExpression.isValidExpression(trimmed)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
        }
        TriggerKey triggerKey = TriggerKey.triggerKey(triggerName, triggerGroup);
        Trigger existing = scheduler.getTrigger(triggerKey);
        if (existing == null) {
            return false;
        }
        // Built fresh rather than via existing.getTriggerBuilder() — that returns a wildcard-
        // captured TriggerBuilder<? extends Trigger> that withSchedule(CronScheduleBuilder...)
        // can't type-check against. Carries over the job association, description, priority and
        // start time by hand instead — only the schedule itself changes.
        Trigger rescheduled = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(existing.getJobKey())
                .withDescription(existing.getDescription())
                .withPriority(existing.getPriority())
                .startAt(existing.getStartTime())
                .withSchedule(CronScheduleBuilder.cronSchedule(trimmed))
                .build();
        scheduler.rescheduleJob(triggerKey, rescheduled);
        return true;
    }

    private JobSummary toSummary(JobKey jobKey, Trigger trigger) throws SchedulerException {
        TriggerKey triggerKey = trigger.getKey();
        Trigger.TriggerState state = scheduler.getTriggerState(triggerKey);
        return new JobSummary(
                jobKey.getName(), jobKey.getGroup(),
                triggerKey.getName(), triggerKey.getGroup(),
                toLocalDateTime(trigger.getStartTime()), toLocalDateTime(trigger.getNextFireTime()),
                triggerType(trigger), state.name(), misfireLabel(trigger), scheduleDescription(trigger));
    }

    private String triggerType(Trigger trigger) {
        if (trigger instanceof CronTrigger) {
            return "CRON";
        }
        if (trigger instanceof SimpleTrigger) {
            return "SIMPLE";
        }
        return "OTHER";
    }

    private String cronExpressionOf(Trigger trigger) {
        return trigger instanceof CronTrigger cronTrigger ? cronTrigger.getCronExpression() : null;
    }

    private String scheduleDescription(Trigger trigger) {
        if (trigger instanceof CronTrigger cronTrigger) {
            return cronTrigger.getCronExpression();
        }
        if (trigger instanceof SimpleTrigger simpleTrigger) {
            return "every " + simpleTrigger.getRepeatInterval() + "ms";
        }
        return null;
    }

    // Quartz has no per-trigger "times missed" counter — a fired-but-missed trigger just
    // re-evaluates its own MisfireInstruction and moves on, nothing is persisted about how many
    // times that happened. This surfaces what WILL happen on a missed fire, not a historical
    // count; a real counter would need a separate TriggerListener persisting its own state.
    private String misfireLabel(Trigger trigger) {
        int instruction = trigger.getMisfireInstruction();
        if (instruction == Trigger.MISFIRE_INSTRUCTION_SMART_POLICY) {
            return "SMART_POLICY";
        }
        if (trigger instanceof CronTrigger) {
            if (instruction == CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW) {
                return "FIRE_ONCE_NOW";
            }
            if (instruction == CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING) {
                return "DO_NOTHING";
            }
        }
        return "UNKNOWN(" + instruction + ")";
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}
