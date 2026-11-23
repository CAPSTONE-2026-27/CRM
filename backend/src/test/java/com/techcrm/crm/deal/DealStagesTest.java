package com.techcrm.crm.deal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pipeline's shape.
 *
 * Worth pinning because three separate things read it: {@code isBefore}, which
 * decides whether a side effect may advance a deal; the bot triggers, which
 * fire on particular stages; and the frontend board, whose column order has to
 * match. A stage inserted in the wrong place breaks all three quietly.
 */
@DisplayName("the deal pipeline")
class DealStagesTest {

    /** A meeting with an opportunity comes after the conversion that created
     *  it, and before the proposal that the meeting informs. */
    @Test
    void putsTheDealMeetingBetweenOpportunityAndProposal() {
        int opportunity = DealStages.ORDERED.indexOf(DealStages.OPPORTUNITY_CREATED);
        int dealMeeting = DealStages.ORDERED.indexOf(DealStages.DEAL_MEETING);
        int proposal = DealStages.ORDERED.indexOf(DealStages.PROPOSAL);

        assertThat(dealMeeting).isGreaterThan(opportunity);
        assertThat(dealMeeting).isLessThan(proposal);
    }

    /** The lead meeting stays in the lead phase; the deal meeting is after
     *  conversion and holds deals. Confusing the two would have the board
     *  render deals as lead cards. */
    @Test
    void keepsTheDealMeetingOutOfTheLeadPhase() {
        assertThat(DealStages.LEAD_PHASE).contains(DealStages.MEETING_SCHEDULED);
        assertThat(DealStages.LEAD_PHASE).doesNotContain(DealStages.DEAL_MEETING);
    }

    @Test
    void ordersTheWholePipelineAsTheBoardShowsIt() {
        assertThat(DealStages.ORDERED).containsExactly(
                DealStages.PROSPECTING,
                DealStages.QUALIFICATION,
                DealStages.MEETING_SCHEDULED,
                DealStages.OPPORTUNITY_CREATED,
                DealStages.DEAL_MEETING,
                DealStages.PROPOSAL,
                DealStages.NEGOTIATION,
                DealStages.CONTRACT,
                DealStages.CLOSED_WON,
                DealStages.CLOSED_LOST);
    }

    /** Booking a meeting advances a deal that has not got there yet, and must
     *  never drag one backwards from a later stage. */
    @Test
    void knowsWhichStagesComeEarlier() {
        assertThat(DealStages.isBefore(DealStages.OPPORTUNITY_CREATED, DealStages.DEAL_MEETING)).isTrue();
        assertThat(DealStages.isBefore(DealStages.PROPOSAL, DealStages.DEAL_MEETING)).isFalse();
        assertThat(DealStages.isBefore(DealStages.CLOSED_WON, DealStages.DEAL_MEETING)).isFalse();
    }

    @Test
    void recognisesTheNewStage() {
        assertThat(DealStages.ALL).contains(DealStages.DEAL_MEETING);
    }
}
