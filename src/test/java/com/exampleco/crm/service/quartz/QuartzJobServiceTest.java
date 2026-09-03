package com.exampleco.crm.service.quartz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// plain Mockito unit test over QuartzJobService's Scheduler-facing logic — mapping,
// cron validation, and each control action calling exactly the one Scheduler method it should.
// No Spring context or datasource, same shape as ChannelAccessServiceTest.
@ExtendWith(MockitoExtension.class)
class QuartzJobServiceTest {

    @Mock
    private Scheduler scheduler;

    private QuartzJobService service;

    @BeforeEach
    void setUp() {
        service = new QuartzJobService(scheduler);
    }

    @Test
    void listJobs_mapsJobKeyAndCronTriggerIntoSummary() throws SchedulerException {
        JobKey jobKey = JobKey.jobKey("notifyFailedTickets", "ticket");
        TriggerKey triggerKey = TriggerKey.triggerKey("notifyFailedTickets", "ticket");
        CronTrigger trigger = mock(CronTrigger.class);
        when(trigger.getKey()).thenReturn(triggerKey);
        when(trigger.getStartTime()).thenReturn(new Date());
        when(trigger.getNextFireTime()).thenReturn(new Date());
        when(trigger.getMisfireInstruction()).thenReturn(Trigger.MISFIRE_INSTRUCTION_SMART_POLICY);
        when(trigger.getCronExpression()).thenReturn("0 0 8 * * ?");
        when(scheduler.getJobKeys(any())).thenReturn(Set.of(jobKey));
        doReturn(List.of(trigger)).when(scheduler).getTriggersOfJob(jobKey);
        when(scheduler.getTriggerState(triggerKey)).thenReturn(Trigger.TriggerState.NORMAL);

        List<QuartzJobService.JobSummary> summaries = service.listJobs();

        assertThat(summaries).hasSize(1);
        QuartzJobService.JobSummary summary = summaries.get(0);
        assertThat(summary.jobName()).isEqualTo("notifyFailedTickets");
        assertThat(summary.jobGroup()).isEqualTo("ticket");
        assertThat(summary.triggerName()).isEqualTo("notifyFailedTickets");
        assertThat(summary.triggerGroup()).isEqualTo("ticket");
        assertThat(summary.type()).isEqualTo("CRON");
        assertThat(summary.status()).isEqualTo("NORMAL");
        assertThat(summary.misfireInstruction()).isEqualTo("SMART_POLICY");
        assertThat(summary.schedule()).isEqualTo("0 0 8 * * ?");
    }

    @Test
    void reschedule_invalidCron_throwsWithoutTouchingScheduler() {
        assertThatThrownBy(() -> service.reschedule("ticket", "notifyFailedTickets", "not a cron"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(scheduler);
    }

    @Test
    void reschedule_validCron_buildsNewCronTriggerAndCallsRescheduleJob() throws SchedulerException {
        TriggerKey triggerKey = TriggerKey.triggerKey("notifyFailedTickets", "ticket");
        JobKey jobKey = JobKey.jobKey("notifyFailedTickets", "ticket");
        CronTrigger existing = mock(CronTrigger.class);
        when(existing.getJobKey()).thenReturn(jobKey);
        when(existing.getDescription()).thenReturn("Seed schedule only");
        when(existing.getPriority()).thenReturn(5);
        when(existing.getStartTime()).thenReturn(new Date());
        when(scheduler.getTrigger(triggerKey)).thenReturn(existing);

        boolean result = service.reschedule("ticket", "notifyFailedTickets", "0 0 9 * * ?");

        assertThat(result).isTrue();
        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).rescheduleJob(eq(triggerKey), captor.capture());
        CronTrigger rescheduled = (CronTrigger) captor.getValue();
        assertThat(rescheduled.getCronExpression()).isEqualTo("0 0 9 * * ?");
        assertThat(rescheduled.getJobKey()).isEqualTo(jobKey);
    }

    @Test
    void reschedule_triggerNotFound_returnsFalseWithoutRescheduling() throws SchedulerException {
        when(scheduler.getTrigger(any())).thenReturn(null);

        boolean result = service.reschedule("ticket", "missing", "0 0 9 * * ?");

        assertThat(result).isFalse();
        verify(scheduler, never()).rescheduleJob(any(), any());
    }

    @Test
    void pauseAll_callsSchedulerPauseAll() throws SchedulerException {
        service.pauseAll();

        verify(scheduler).pauseAll();
    }

    @Test
    void resumeAll_callsSchedulerResumeAll() throws SchedulerException {
        service.resumeAll();

        verify(scheduler).resumeAll();
    }

    @Test
    void pauseTrigger_existingTrigger_pausesAndReturnsTrue() throws SchedulerException {
        TriggerKey key = TriggerKey.triggerKey("notifyFailedTickets", "ticket");
        when(scheduler.checkExists(key)).thenReturn(true);

        assertThat(service.pauseTrigger("ticket", "notifyFailedTickets")).isTrue();
        verify(scheduler).pauseTrigger(key);
    }

    @Test
    void pauseTrigger_missingTrigger_returnsFalseWithoutPausing() throws SchedulerException {
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        assertThat(service.pauseTrigger("ticket", "missing")).isFalse();
        verify(scheduler, never()).pauseTrigger(any());
    }

    @Test
    void resumeTrigger_existingTrigger_resumesAndReturnsTrue() throws SchedulerException {
        TriggerKey key = TriggerKey.triggerKey("notifyFailedTickets", "ticket");
        when(scheduler.checkExists(key)).thenReturn(true);

        assertThat(service.resumeTrigger("ticket", "notifyFailedTickets")).isTrue();
        verify(scheduler).resumeTrigger(key);
    }

    @Test
    void resumeTrigger_missingTrigger_returnsFalseWithoutResuming() throws SchedulerException {
        when(scheduler.checkExists(any(TriggerKey.class))).thenReturn(false);

        assertThat(service.resumeTrigger("ticket", "missing")).isFalse();
        verify(scheduler, never()).resumeTrigger(any());
    }

    @Test
    void triggerNow_existingTrigger_firesJobAndReturnsTrue() throws SchedulerException {
        JobKey jobKey = JobKey.jobKey("notifyFailedTickets", "ticket");
        Trigger trigger = mock(Trigger.class);
        when(trigger.getJobKey()).thenReturn(jobKey);
        when(scheduler.getTrigger(any())).thenReturn(trigger);

        assertThat(service.triggerNow("ticket", "notifyFailedTickets")).isTrue();
        verify(scheduler).triggerJob(jobKey);
    }

    @Test
    void triggerNow_missingTrigger_returnsFalseWithoutFiring() throws SchedulerException {
        when(scheduler.getTrigger(any())).thenReturn(null);

        assertThat(service.triggerNow("ticket", "missing")).isFalse();
        verify(scheduler, never()).triggerJob(any(JobKey.class));
    }
}
