package com.techcrm.crm.lead;

/**
 * A lead was created and saved.
 *
 * Published from the one method every capture path funnels through, and
 * consumed after the transaction commits — a welcome email naming a lead that
 * then rolled back would be worse than no email at all.
 *
 * Carries values rather than the entity: the listener runs on another thread,
 * where a detached entity is a lazy-loading exception waiting to happen.
 */
public record LeadCreated(
        Long organizationId,
        Long leadId,
        String fullName,
        String email,
        String company,
        String sourceChannel
) {
}
