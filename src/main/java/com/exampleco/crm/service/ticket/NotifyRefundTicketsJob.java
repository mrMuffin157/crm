package com.exampleco.crm.service.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.quartz.JobExecutionContext;

// Replaces legacy-app's own 15:10 daily NotifyRefundTicketsJob — a read-only job over
// ticket data that's fully in crm_dev now, run through Quartz's JDBC JobStore instead
// of Grails' cron trigger, same shape as NotifyFailedTicketsJob. See config.quartz.QuartzConfig
// for the JobDetail/Trigger beans that register it.
//
// Field-injected rather than constructor-injected: Quartz instantiates this class itself via a
// no-arg constructor on every fire, then Spring Boot's autoconfigured job factory autowires
// @Autowired fields on that instance afterward. Deliberately NOT a @Component — Quartz needs a
// fresh instance per execution, not a shared singleton bean.
public class NotifyRefundTicketsJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(NotifyRefundTicketsJob.class);

    @Autowired
    private NotifyRefundTicketsService notifyRefundTicketsService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        int count = notifyRefundTicketsService.run();
        log.info("Found {} open Refund ticket(s) for the daily digest", count);
    }
}
