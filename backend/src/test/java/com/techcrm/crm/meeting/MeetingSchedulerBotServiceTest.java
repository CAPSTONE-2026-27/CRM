package com.techcrm.crm.meeting;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.meeting.MeetingDtos.ScheduleMeetingRequest;
import com.techcrm.crm.sap.SapProcessProperties;
import com.techcrm.crm.sap.SapProcessTriggerClient;
import com.techcrm.crm.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("scheduling a meeting with a lead")
class MeetingSchedulerBotServiceTest {

    private static final Long ORG_ID = 8L;

    @Mock LeadRepository leadRepository;
    @Mock com.techcrm.crm.deal.DealRepository dealRepository;
    @Mock SapProcessTriggerClient sap;

    private SapProcessProperties properties;
    private MeetingSchedulerBotService service;

    @BeforeEach
    void setUp() {
        properties = new SapProcessProperties();
        properties.setMeetingSchedulerDefinitionId("lead-scheduler-def");
        properties.setDealMeetingSchedulerDefinitionId("deal-scheduler-def");
        service = new MeetingSchedulerBotService(leadRepository, dealRepository, sap, properties);
        when(sap.startProcess(anyString(), any())).thenReturn("instance-1");
    }

    /**
     * Every value the invitation needs, under the names the process declares.
     *
     * Lower case, because the platform matches context keys exactly against
     * the declared inputs — a camel-cased key is reported as missing rather
     * than normalised.
     */
    @Test
    void sendsEverythingTheInvitationNeeds() {
        leadWithEmail("rajesh@novatech.com");

        service.schedule(caller(), 43L, request());

        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(eq("lead-scheduler-def"), context.capture());
        assertThat(context.getValue()).containsOnlyKeys(
                "leadid", "leadname", "leademail", "meetingdate", "meetingtime", "meetinglink");
        assertThat(context.getValue()).containsEntry("leadid", "43");
        assertThat(context.getValue()).containsEntry("leadname", "Rajesh Kumar");
        assertThat(context.getValue()).containsEntry("leademail", "rajesh@novatech.com");
        assertThat(context.getValue()).containsEntry("meetingdate", "2026-11-03");
        assertThat(context.getValue()).containsEntry("meetingtime", "14:30");
        assertThat(context.getValue()).containsEntry("meetinglink", "https://meet.example/abc");
    }

    /**
     * A converted lead's meeting is about the opportunity.
     *
     * The customer is the same person, so the address does not change — but
     * the invitation names the deal, because that is what they will be
     * discussing rather than the enquiry it grew out of.
     */
    @Test
    void namesTheDealOnceTheLeadHasBeenConverted() {
        Lead lead = leadWithEmail("rajesh@novatech.com");
        lead.setConvertedDealId(77L);
        dealIs(77L, "OPP-000077", "NovaTech — Enterprise Plan");

        var response = service.schedule(caller(), 43L, request());

        assertThat(response.dealId()).isEqualTo("77");
        assertThat(response.opportunityId()).isEqualTo("OPP-000077");
        assertThat(response.dealName()).isEqualTo("NovaTech — Enterprise Plan");

        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(eq("deal-scheduler-def"), context.capture());
        assertThat(context.getValue()).containsOnlyKeys(
                "dealid", "dealname", "leademail", "meetingdate", "meetingtime", "meetinglink");
        assertThat(context.getValue()).containsEntry("dealid", "77");
        assertThat(context.getValue()).containsEntry("dealname", "NovaTech — Enterprise Plan");
        assertThat(context.getValue()).containsEntry("leademail", "rajesh@novatech.com");
    }

    /** A lead that has not been converted goes to the lead bot, and carries no
     *  deal fields at all — each process declares only what it needs. */
    @Test
    void sendsNoDealFieldsForALeadNotYetConverted() {
        leadWithEmail("rajesh@novatech.com");

        var response = service.schedule(caller(), 43L, request());

        assertThat(response.dealId()).isNull();
        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(eq("lead-scheduler-def"), context.capture());
        assertThat(context.getValue()).doesNotContainKeys("dealid", "dealname");
    }

    /** One bot missing does not disable the other. */
    @Test
    void stillInvitesALeadWhenOnlyTheDealBotIsMissing() {
        properties.setDealMeetingSchedulerDefinitionId("");
        leadWithEmail("rajesh@novatech.com");

        var response = service.schedule(caller(), 43L, request());

        assertThat(response.invitationStarted()).isTrue();
    }

    @Test
    void reportsWhereTheInvitationWent() {
        leadWithEmail("rajesh@novatech.com");

        var response = service.schedule(caller(), 43L, request());

        assertThat(response.leadEmail()).isEqualTo("rajesh@novatech.com");
        assertThat(response.leadName()).isEqualTo("Rajesh Kumar");
        assertThat(response.invitationStarted()).isTrue();
    }

    /** There is nobody to invite, and booking a meeting the lead never hears
     *  about is worse than refusing to book it. */
    @Test
    void refusesALeadWithNoEmailAddress() {
        leadWithEmail(null);

        assertThatThrownBy(() -> service.schedule(caller(), 43L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no email address");

        verify(sap, never()).startProcess(anyString(), any());
    }

    @Test
    void refusesALeadInAnotherOrganization() {
        when(leadRepository.findByIdAndOrganizationId(43L, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.schedule(caller(), 43L, request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    /**
     * A platform that cannot be reached does not fail the booking.
     *
     * The representative is told plainly that nobody was invited, which is
     * more use than an error that loses the meeting details they just typed.
     */
    @Test
    void saysSoWhenNoInvitationCouldBeSent() {
        when(sap.startProcess(anyString(), any())).thenReturn(null);
        leadWithEmail("rajesh@novatech.com");

        var response = service.schedule(caller(), 43L, request());

        assertThat(response.invitationStarted()).isFalse();
        assertThat(response.meetingLink()).isEqualTo("https://meet.example/abc");
    }

    @Test
    void saysSoWhenNoSchedulerBotIsConfigured() {
        properties.setMeetingSchedulerDefinitionId("");
        properties.setDealMeetingSchedulerDefinitionId("");
        leadWithEmail("rajesh@novatech.com");

        var response = service.schedule(caller(), 43L, request());

        assertThat(response.invitationStarted()).isFalse();
        verify(sap, never()).startProcess(anyString(), any());
    }

    /* ---------------------------------------------------------------- helpers */

    private Lead leadWithEmail(String email) {
        Lead lead = new Lead();
        lead.setId(43L);
        lead.setOrganizationId(ORG_ID);
        lead.setFullName("Rajesh Kumar");
        lead.setEmail(email);
        when(leadRepository.findByIdAndOrganizationId(43L, ORG_ID)).thenReturn(Optional.of(lead));
        return lead;
    }

    private void dealIs(Long id, String opportunityId, String name) {
        com.techcrm.crm.deal.Deal deal = new com.techcrm.crm.deal.Deal();
        deal.setId(id);
        deal.setOrganizationId(ORG_ID);
        deal.setOpportunityId(opportunityId);
        deal.setName(name);
        when(dealRepository.findByIdAndOrganizationId(id, ORG_ID)).thenReturn(Optional.of(deal));
    }

    private ScheduleMeetingRequest request() {
        return new ScheduleMeetingRequest(LocalDate.of(2026, 11, 3), "14:30", "https://meet.example/abc");
    }

    private AuthenticatedUser caller() {
        return new AuthenticatedUser(9L, ORG_ID, Role.SALES_REP, List.of(), false);
    }
}
