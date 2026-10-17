package com.techcrm.crm.lead.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes validation runs nobody is waiting on any more.
 *
 * A run is worth keeping only while someone might still ask why a lead was
 * refused. An hour is generous for that, and without a sweep the table grows by
 * one row per lead submitted, for ever.
 *
 * A separate bean rather than a scheduled method on the service, for the usual
 * proxying reason: Spring cannot intercept a call an object makes to itself, so
 * the transaction on the delete would be silently ignored if both lived in the
 * same class.
 */
@Component
public class LeadValidationSweep {

    private static final Logger log = LoggerFactory.getLogger(LeadValidationSweep.class);

    private final LeadValidationService validationService;

    public LeadValidationSweep(LeadValidationService validationService) {
        this.validationService = validationService;
    }

    /** Hourly, on the hour. */
    @Scheduled(cron = "0 0 * * * *")
    public void purge() {
        try {
            int removed = validationService.purgeOlderThanOneHour();
            if (removed > 0) {
                log.info("Removed {} expired lead validation run(s)", removed);
            }
        } catch (RuntimeException e) {
            // Housekeeping must never take the scheduler down with it.
            log.warn("Could not purge expired lead validation runs", e);
        }
    }
}
