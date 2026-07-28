package com.techcrm.crm.meeting;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
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
    private final String modelVersion;

    public LeadMeetingService(LeadMeetingRepository meetingRepository,
                              LeadRepository leadRepository,
                              MeetingAnalysisClient analysisClient,
                              @Value("${ai.model-name:unknown}") String modelVersion) {
        this.meetingRepository = meetingRepository;
        this.leadRepository = leadRepository;
        this.analysisClient = analysisClient;
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

        MeetingAnalysisResult result = analysisClient.analyze(new MeetingAnalysisRequest(
                lead.getFullName(), lead.getCompany(), lead.getIndustry(), lead.getProduct(),
                lead.getEstimatedDealValue(), lead.getNotes(), lead.getAiScore(),
                input.meetingDate().toString(), input.meetingTime(), input.meetingOutput()));

        Integer previous = lead.getAiScore();
        if (result == null || result.score() == null) {
            // Model unavailable — hand back the rep's own notes as the starting
            // summary and leave the score unchanged for them to set by hand.
            return new MeetingAnalysisResponse(
                    String.valueOf(lead.getId()), lead.getFullName(),
                    input.meetingDate(), input.meetingTime(), input.meetingOutput(),
                    input.meetingOutput(), previous, previous == null ? 0 : previous, 0,
                    labelForScore(previous == null ? 0 : previous),
                    List.of("AI analysis unavailable — review the score manually"));
        }

        int updated = clampScore(result.score());
        return new MeetingAnalysisResponse(
                String.valueOf(lead.getId()), lead.getFullName(),
                input.meetingDate(), input.meetingTime(), input.meetingOutput(),
                result.summary() == null ? input.meetingOutput() : result.summary(),
                previous, updated, updated - (previous == null ? updated : previous),
                normaliseLabel(result.label(), updated),
                result.reasons() == null ? List.of() : result.reasons());
    }

    /** Save step: appends a history row and rolls the lead's live score forward. */
    @Transactional
    public MeetingResponse save(AuthenticatedUser caller, Long leadId, SaveMeetingRequest request) {
        Lead lead = requireLead(caller, leadId);

        // Snapshotted from the lead rather than trusted from the request, so a
        // crafted payload can't rewrite the scoring trail.
        Integer previousScore = lead.getAiScore();
        int updatedScore = clampScore(request.updatedScore());
        String label = normaliseLabel(request.scoreLabel(), updatedScore);

        LeadMeeting meeting = new LeadMeeting();
        meeting.setOrganizationId(caller.organizationId());
        meeting.setLeadId(lead.getId());
        meeting.setRecordedById(caller.userId());
        meeting.setMeetingDate(request.meetingDate());
        meeting.setMeetingTime(request.meetingTime());
        meeting.setMeetingOutput(request.meetingOutput());
        meeting.setAiSummary(request.aiSummary());
        meeting.setPreviousScore(previousScore);
        meeting.setUpdatedScore(updatedScore);
        meeting.setScoreChangeReason(request.scoreChangeReason());
        meeting.setAiModelVersion(modelVersion);
        LeadMeeting saved = meetingRepository.save(meeting);

        lead.setAiScore(updatedScore);
        lead.setAiScoreLabel(label + " lead");
        lead.setAiScoreReason(request.scoreChangeReason() == null || request.scoreChangeReason().isBlank()
                ? truncate(request.aiSummary())
                : request.scoreChangeReason());
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
