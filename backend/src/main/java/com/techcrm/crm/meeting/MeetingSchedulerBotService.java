package com.techcrm.crm.meeting;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealRepository;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.meeting.MeetingDtos.ScheduleMeetingRequest;
import com.techcrm.crm.meeting.MeetingDtos.ScheduleMeetingResponse;
import com.techcrm.crm.sap.SapProcessProperties;
import com.techcrm.crm.sap.SapProcessTriggerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Booking a meeting with a lead and having the automation invite them.
 *
 * The invitation is sent by the automation rather than by this application,
 * which is the point of the bot: a meeting invitation is a message to a
 * customer, and sending it from the process platform is what the platform is
 * for. This side gathers what the invitation needs and hands it over.
 *
 * <b>The lead's address is read here, not asked for.</b> It is already on the
 * record, and a form field for it would let a representative mistype the one
 * value the whole invitation depends on.
 */
@Service
public class MeetingSchedulerBotService {

    private static final Logger log = LoggerFactory.getLogger(MeetingSchedulerBotService.class);

    private final LeadRepository leadRepository;
    private final DealRepository dealRepository;
    private final SapProcessTriggerClient sap;
    private final SapProcessProperties properties;

    public MeetingSchedulerBotService(LeadRepository leadRepository,
                                      DealRepository dealRepository,
                                      SapProcessTriggerClient sap,
                                      SapProcessProperties properties) {
        this.leadRepository = leadRepository;
        this.dealRepository = dealRepository;
        this.sap = sap;
        this.properties = properties;
    }

    /**
     * Schedules the meeting and starts the automation that invites the lead.
     *
     * @throws ResponseStatusException 404 unknown lead, 409 the lead has no
     *         address to invite
     */
    @Transactional(readOnly = true)
    public ScheduleMeetingResponse schedule(AuthenticatedUser caller, Long leadId, ScheduleMeetingRequest request) {
        Lead lead = leadRepository.findByIdAndOrganizationId(leadId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));

        String email = lead.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    lead.getFullName() + " has no email address, so there is nobody to send the invitation to. "
                            + "Add one to the lead first.");
        }

        // A converted lead's meeting is with the opportunity, not the enquiry
        // it grew out of. The invitation names the deal so the customer reads
        // about the thing being discussed rather than a record they never saw.
        Deal deal = lead.getConvertedDealId() == null
                ? null
                : dealRepository.findByIdAndOrganizationId(lead.getConvertedDealId(), caller.organizationId())
                        .orElse(null);

        boolean started = startBot(lead, deal, request, email);

        return new ScheduleMeetingResponse(
                String.valueOf(lead.getId()),
                lead.getFullName(),
                email,
                deal == null ? null : String.valueOf(deal.getId()),
                deal == null ? null : firstNonBlank(deal.getOpportunityId(), String.valueOf(deal.getId())),
                deal == null ? null : deal.getName(),
                request.meetingDate(),
                request.meetingTime(),
                request.meetingLink().trim(),
                started);
    }

    /**
     * Hands the invitation to the automation.
     *
     * Lower case keys throughout, because the platform stores process input
     * names that way and matches them exactly — a camel-cased key is reported
     * as a missing property rather than being quietly normalised.
     *
     * Not asynchronous, unlike the document bots: the representative is told
     * whether the invitation was sent, so there is something to wait for. It is
     * one call and the platform answers in milliseconds.
     */
    private boolean startBot(Lead lead, Deal deal, ScheduleMeetingRequest request, String email) {
        boolean aboutADeal = deal != null;
        String kind = aboutADeal ? "deal" : "lead";

        String definitionId = aboutADeal
                ? properties.getDealMeetingSchedulerDefinitionId()
                : properties.getMeetingSchedulerDefinitionId();

        if (definitionId == null || definitionId.isBlank()) {
            log.warn("No {} meeting scheduler bot is configured; no invitation was sent for lead {}",
                    kind, lead.getId());
            return false;
        }

        // Each process declares only what its own invitation needs, and the
        // platform matches context keys against that set exactly — so the two
        // contexts differ in their first two entries and share the rest.
        Map<String, Object> context = new LinkedHashMap<>();
        if (aboutADeal) {
            context.put("dealid", String.valueOf(deal.getId()));
            context.put("dealname", nullToEmpty(deal.getName()));
        } else {
            context.put("leadid", String.valueOf(lead.getId()));
            context.put("leadname", nullToEmpty(lead.getFullName()));
        }
        // The same person either way: converting a lead does not change who
        // the invitation goes to, only what it is about.
        context.put("leademail", email.trim());
        context.put("meetingdate", request.meetingDate().toString());
        context.put("meetingtime", request.meetingTime());
        context.put("meetinglink", request.meetingLink().trim());

        String instanceId = sap.startProcess(definitionId, context);
        if (instanceId == null) {
            log.warn("Could not start the {} meeting scheduler bot for lead {}", kind, lead.getId());
            return false;
        }
        log.info("Started the {} meeting scheduler bot for lead {} as instance {}",
                kind, lead.getId(), instanceId);
        return true;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
