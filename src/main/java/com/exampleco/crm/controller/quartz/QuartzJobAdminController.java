package com.exampleco.crm.controller.quartz;

import org.quartz.SchedulerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import com.exampleco.crm.service.quartz.QuartzJobService;

// Admin-only Quartz job control API — list/pause/resume/trigger-now/reschedule every
// job registered with the app's Scheduler. Covered by SecurityConfig's existing
// .pathMatchers("/api/admin/**").hasRole(Role.ADMIN) — no SecurityConfig change needed.
// Every org.quartz.Scheduler call is blocking (JDBC-backed), so QuartzJobService's calls are
// wrapped in Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()) here exactly like
// every other blocking call in AppUserAdminController.
@RestController
@RequestMapping("/api/admin/quartz-jobs")
public class QuartzJobAdminController {

    private final QuartzJobService quartzJobService;

    public QuartzJobAdminController(QuartzJobService quartzJobService) {
        this.quartzJobService = quartzJobService;
    }

    public record RescheduleRequest(String cronExpression) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<QuartzJobService.JobSummary>> listJobs() {
        return Mono.fromCallable(quartzJobService::listJobs).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/triggers/{triggerGroup}/{triggerName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<QuartzJobService.TriggerDetail>> getTriggerDetail(@PathVariable String triggerGroup,
                                                                                  @PathVariable String triggerName) {
        return Mono.fromCallable(() -> {
            QuartzJobService.TriggerDetail detail = quartzJobService.getTriggerDetail(triggerGroup, triggerName);
            return detail == null ? ResponseEntity.notFound().<QuartzJobService.TriggerDetail>build() : ResponseEntity.ok(detail);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/pause-all")
    public Mono<ResponseEntity<Void>> pauseAll() {
        return Mono.fromCallable(() -> {
            quartzJobService.pauseAll();
            return ResponseEntity.ok().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/resume-all")
    public Mono<ResponseEntity<Void>> resumeAll() {
        return Mono.fromCallable(() -> {
            quartzJobService.resumeAll();
            return ResponseEntity.ok().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/triggers/{triggerGroup}/{triggerName}/pause")
    public Mono<ResponseEntity<Void>> pauseTrigger(@PathVariable String triggerGroup, @PathVariable String triggerName) {
        return Mono.fromCallable(() -> toEmptyResponse(quartzJobService.pauseTrigger(triggerGroup, triggerName)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/triggers/{triggerGroup}/{triggerName}/resume")
    public Mono<ResponseEntity<Void>> resumeTrigger(@PathVariable String triggerGroup, @PathVariable String triggerName) {
        return Mono.fromCallable(() -> toEmptyResponse(quartzJobService.resumeTrigger(triggerGroup, triggerName)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/triggers/{triggerGroup}/{triggerName}/trigger-now")
    public Mono<ResponseEntity<Void>> triggerNow(@PathVariable String triggerGroup, @PathVariable String triggerName) {
        return Mono.fromCallable(() -> toEmptyResponse(quartzJobService.triggerNow(triggerGroup, triggerName)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping(value = "/triggers/{triggerGroup}/{triggerName}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> reschedule(@PathVariable String triggerGroup, @PathVariable String triggerName,
                                                  @RequestBody RescheduleRequest request) {
        return Mono.fromCallable(() ->
                toEmptyResponse(quartzJobService.reschedule(triggerGroup, triggerName, request.cronExpression())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Void> toEmptyResponse(boolean found) {
        return found ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // reschedule()'s own cron validation throws this before ever touching the scheduler.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    // Every org.quartz.Scheduler call declares this checked exception — surfaced as a clean 500
    // instead of leaking a raw stack trace, same reasoning as
    // AppUserAdminController#handleDataIntegrityViolation.
    @ExceptionHandler(SchedulerException.class)
    public ResponseEntity<Map<String, String>> handleSchedulerException(SchedulerException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Scheduler error: " + e.getMessage()));
    }
}
