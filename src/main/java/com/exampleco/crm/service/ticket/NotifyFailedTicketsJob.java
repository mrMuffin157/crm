package com.exampleco.crm.service.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.quartz.JobExecutionContext;

// Daily job for NotifyFailedTicketsService. Runs through Quartz's JDBC
// JobStore instead of plain @Scheduled — see config.quartz.QuartzConfig for the
// JobDetail/Trigger beans that register it, and the Scheduled Jobs admin UI for pause/resume/
// trigger-now/reschedule.
//
// Field-injected rather than constructor-injected: Quartz instantiates this class itself via a
// no-arg constructor on every fire, then Spring Boot's autoconfigured job factory autowires
// @Autowired fields on that instance afterward. Deliberately NOT a @Component — Quartz needs a
// fresh instance per execution, not a shared singleton bean.
public class NotifyFailedTicketsJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(NotifyFailedTicketsJob.class);

    @Autowired
    private NotifyFailedTicketsService notifyFailedTicketsService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        int count = notifyFailedTicketsService.run();
        log.info("Found {} ProcessFailed ticket(s) for the daily digest", count);
    }
}
