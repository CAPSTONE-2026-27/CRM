package com.techcrm.crm.meeting;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.lead.score.LeadScoreService;
import com.techcrm.crm.meeting.MeetingAnalysisClient.MeetingAnalysisRequest;
import com.techcrm.crm.meeting.MeetingAnalysisClient.MeetingAnalysisResult;
import com.techcrm.crm.meeting.MeetingDtos.MeetingAnalysisResponse;
import com.techcrm.crm.meeting.MeetingDtos.MeetingInput;
import com.techcrm.crm.meeting.MeetingDtos.MeetingResponse;
import com.techcrm.crm.meeting.MeetingDtos.SaveMeetingRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class LeadMeetingService {

    private static final Set<String> LABELS = Set.of("Hot", "Warm", "Cold");

    private final LeadMeetingRepository meetingRepository;
    private final LeadRepository leadRepository;
    private final MeetingAnalysisClient analysisClient;
    private final LeadScoreService leadScoreService;
    private final String modelVersion;

    public LeadMeetingService(LeadMeetingRepository meetingRepository,
                              LeadRepository leadRepository,
                              MeetingAnalysisClient analysisClient,
                              LeadScoreService leadScoreService,
                              @Value("${ai.model-name:unknown}") String modelVersion) {
        this.meetingRepository = meetingRepository;
        this.leadRepository = leadRepository;
        this.analysisClient = analysisClient;
        this.leadScoreService = leadScoreService;
        this.modelVersion = modelVersion;
    }

    @Transactional(readOnly = true)
    public List<MeetingResponse> history(AuthenticatedUser caller, Long leadId) {
        requireLead(caller, leadId);
        return meetingRepository
                .findByLeadIdAndOrganizationIdOrderByMeetingDateDescCreatedAtDesc(leadId, caller.organizationId())
                .stream().map(MeetingResponse::from).toList();
    }

    /** Preview step: summarise and re-score without persisting, so the rep can
     *  review and edit before committing anything. */
    @Transactional(readOnly = true)
    public MeetingAnalysisResponse analyze(AuthenticatedUser caller, Long leadId, MeetingInput input) {
        Lead lead = requireLead(caller, leadId);

        // Extraction + rule engine, the same path save() takes. The preview must
        // show the number the save will actually produce; a preview computed a
        // different way would let the two disagree, and the rep would rightly
        // stop trusting either.
        //
        // The reasons list now carries the per-parameter breakdown rather than
        // the model's prose. It is a better answer to "why did it move" — each
        // line is a signal, its value, and the points it earned out of its
        // maximum, which the rep can check against their own notes.
        int meetingNumber = 1 + (int) meetingRepository
                .countByLeadIdAndOrganizationId(leadId, caller.organizationId());
        var outcome = leadScoreService.analyse(
                lead, input.meetingOutput(), meetingNumber, input.meetingDate());
        var score = outcome.score();

        List<String> reasons = score.contributions().stream()
                .map(c -> "%s: %s (%d/%d)".formatted(
                        readable(c.parameter()), c.value(), c.points(), c.maxPoints()))
                .toList();

        // The rep's own notes seed the summary field, which they then edit
        // before saving. The extraction model is not asked to write prose --
        // it was trained to read five signals, and asking it for a summary as
        // well would be a second task it never saw.
        return new MeetingAnalysisResponse(
                String.valueOf(lead.getId()), lead.getFullName(),
                input.meetingDate(), input.meetingTime(), input.meetingOutput(),
                input.meetingOutput(),
                score.previousScore(), score.updatedScore(), score.scoreDifference(),
                labelForScore(score.updatedScore()),
                reasons);
    }

    private String readable(String parameter) {
        StringBuilder out = new StringBuilder();
        for (String word : parameter.split("_")) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** Save step: appends a history row and rolls the lead's live score forward. */
    @Transactional
    public MeetingResponse save(AuthenticatedUser caller, Long leadId, SaveMeetingRequest request) {
        Lead lead = requireLead(caller, leadId);

        Integer previousScore = lead.getAiScore();

        // The meeting row is written first so the analysis and history rows can
        // reference it. Its score columns are filled in below, once the engine
        // has produced them.
        LeadMeeting meeting = new LeadMeeting();
        meeting.setOrganizationId(caller.organizationId());
        meeting.setLeadId(lead.getId());
        meeting.setRecordedById(caller.userId());
        meeting.setMeetingDate(request.meetingDate());
        meeting.setMeetingTime(request.meetingTime());
        meeting.setMeetingOutput(request.meetingOutput());
        meeting.setAiSummary(request.aiSummary());
        meeting.setPreviousScore(previousScore);
        meeting.setAiModelVersion(modelVersion);
        LeadMeeting saved = meetingRepository.save(meeting);

        // The score is computed here, from the notes that were actually saved.
        //
        // request.updatedScore() is deliberately ignored. It used to be written
        // straight through, which meant the number on a lead was whatever the
        // client sent -- editable in the UI, and forgeable by anyone who could
        // call the API. The field is still accepted so the existing frontend
        // does not start failing validation, but nothing reads it.
        //
        // LeadScoreService re-reads the notes rather than trusting the preview:
        // the rep may have edited them between previewing and saving, and the
        // stored reading must describe the text that was stored.
        var outcome = leadScoreService.recordMeeting(
                caller, lead, saved.getId(), request.meetingOutput(), request.meetingDate());

        var score = outcome.score();
        saved.setUpdatedScore(score.updatedScore());
        saved.setScoreChangeReason(score.changeReason());
        saved = meetingRepository.save(saved);

        // recordMeeting already set aiScore, leadPriority, qualificationProbability
        // and aiScoreReason. Only the Hot/Warm/Cold temperature is this service's
        // to own, since it drives the existing status column and filters.
        String label = labelForScore(score.updatedScore());
        lead.setAiScoreLabel(label + " lead");
        lead.setStatus(label.toUpperCase());
        leadRepository.save(lead);

        return MeetingResponse.from(saved);
    }

    private Lead requireLead(AuthenticatedUser caller, Long leadId) {
        return leadRepository.findByIdAndOrganizationId(leadId, caller.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
    }

    private int clampScore(Integer score) {
        if (score == null) return 0;
        return Math.max(0, Math.min(100, score));
    }

    private String normaliseLabel(String label, int score) {
        if (label != null) {
            String trimmed = label.trim();
            for (String candidate : LABELS) {
                if (candidate.equalsIgnoreCase(trimmed)) return candidate;
            }
        }
        return labelForScore(score);
    }

    private String labelForScore(int score) {
        if (score >= 75) return "Hot";
        if (score >= 45) return "Warm";
        return "Cold";
    }

    private String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
