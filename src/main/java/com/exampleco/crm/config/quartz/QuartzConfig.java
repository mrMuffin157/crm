package com.exampleco.crm.config.quartz;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.exampleco.crm.service.ticket.NotifyFailedTicketsJob;
import com.exampleco.crm.service.ticket.NotifyRefundTicketsJob;

// JobDetail/Trigger beans for every Quartz job in this app. Spring Boot's Quartz
// autoconfiguration registers these with the Scheduler on startup — but only creates the
// job/trigger if that key doesn't already exist in QRTZ_* (spring.quartz.overwrite-existing-jobs
// =false), so the cron expression here is only ever a SEED value; a live reschedule via the
// Scheduled Jobs admin UI is not overwritten by a later restart or a changed env var.
//
// Job/trigger key GROUP is the feature name (e.g. "ticket") — the same axis this codebase
// already subpackages controllers/services/config by, so a future job for a different feature
// naturally gets its own group instead of everything landing in one flat bucket.
@Configuration
public class QuartzConfig {

    public static final String TICKET_JOB_GROUP = "ticket";

    @Bean
    public JobDetail notifyFailedTicketsJobDetail() {
        return JobBuilder.newJob(NotifyFailedTicketsJob.class)
                .withIdentity(JobKey.jobKey("notifyFailedTickets", TICKET_JOB_GROUP))
                .withDescription("Daily digest of ProcessFailed tickets to every ROLE_ADMIN")
                // storeDurably: keep the JobDetail row even if an admin's trigger is ever
                // removed independently — otherwise Quartz drops the job along with it.
                .storeDurably(true)
                // requestRecovery: re-fire if the JVM dies mid-execution. Safe here since
                // NotifyFailedTicketsService's own mail-publish failure is already "caught and
                // logged, never allowed to fail the job" — a recovery re-fire is at worst a
                // duplicate digest, not data corruption.
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger notifyFailedTicketsTrigger(JobDetail notifyFailedTicketsJobDetail,
                                               @Value("${app.notify-failed-tickets.cron}") String cron) {
        return TriggerBuilder.newTrigger()
                .forJob(notifyFailedTicketsJobDetail)
                .withIdentity(TriggerKey.triggerKey("notifyFailedTickets", TICKET_JOB_GROUP))
                .withDescription("Seed schedule only — see spring.quartz.overwrite-existing-jobs=false")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                .build();
    }

    @Bean
    public JobDetail notifyRefundTicketsJobDetail() {
        return JobBuilder.newJob(NotifyRefundTicketsJob.class)
                .withIdentity(JobKey.jobKey("notifyRefundTickets", TICKET_JOB_GROUP))
                .withDescription("Daily digest of open Refund tickets to NOTIFY_REFUND_TICKET_MEMBER (US channels) "
                        + "or the gsmAlarmGroup (everything else)")
                .storeDurably(true)
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger notifyRefundTicketsTrigger(JobDetail notifyRefundTicketsJobDetail,
                                               @Value("${app.notify-refund-tickets.cron}") String cron) {
        return TriggerBuilder.newTrigger()
                .forJob(notifyRefundTicketsJobDetail)
                .withIdentity(TriggerKey.triggerKey("notifyRefundTickets", TICKET_JOB_GROUP))
                .withDescription("Seed schedule only — see spring.quartz.overwrite-existing-jobs=false")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                .build();
    }
}
