package com.techcrm.crm.deal;

/**
 * A deal moved to a new stage.
 *
 * Published from the one place every stage change passes through, and consumed
 * after the transaction commits — a listener that fired any earlier could act
 * on a change that then rolled back, and the automation it starts would read a
 * stage that never existed.
 *
 * Carries values rather than the entity: the listener runs on another thread,
 * where a detached entity is a lazy-loading exception waiting to happen.
 */
public record DealStageChanged(Long organizationId, Long dealId, String stage) {
}
