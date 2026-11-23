package com.techcrm.crm.meeting;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class MeetingDtos {

    private MeetingDtos() {
    }

    /**
     * Booking a meeting with a lead, which the automation then invites them to.
     *
     * Nothing here describes what was discussed: this is a meeting that has not
     * happened yet. What came of it is recorded separately, from the pipeline
     * board, where the model re-scores the lead from the notes.
     */
    public record ScheduleMeetingRequest(
            @NotNull LocalDate meetingDate,
            @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "Meeting time must be HH:mm") String meetingTime,

            /** Where the meeting happens. Sent to the lead, so it is the one
             *  field a person cannot be asked to guess. */
            @NotBlank @Size(max = 500) String meetingLink
    ) {
    }

    /** What the scheduling request produced, so the caller can say where the
     *  invitation went. */
    /**
     * @param dealId null while the lead is still a lead. Present once it has
     *        been converted, because then the meeting is about the opportunity
     *        rather than the enquiry it grew out of.
     */
    public record ScheduleMeetingResponse(
            String leadId,
            String leadName,
            String leadEmail,
            String dealId,
            String opportunityId,
            String dealName,
            LocalDate meetingDate,
            String meetingTime,
            String meetingLink,
            boolean invitationStarted
    ) {
    }

    /** Input for both the preview (analyze) and save steps. */
    public record MeetingInput(
            @NotNull LocalDate meetingDate,
            @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "Meeting time must be HH:mm") String meetingTime,
            @NotBlank String meetingOutput
    ) {
    }

    /** Persisted after the rep has reviewed, and possibly edited, the summary. */
    public record SaveMeetingRequest(
            @NotNull LocalDate meetingDate,
            @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "Meeting time must be HH:mm") String meetingTime,
            @NotBlank String meetingOutput,
            @NotBlank String aiSummary,
            @NotNull @Min(0) @Max(100) Integer updatedScore,
            String scoreLabel,
            String scoreChangeReason
    ) {
    }

    /** Preview only — nothing is persisted until the rep saves. */
    public record MeetingAnalysisResponse(
            String leadId,
            String leadName,
            LocalDate meetingDate,
            String meetingTime,
            String meetingOutput,
            String aiSummary,
            Integer previousScore,
            Integer updatedScore,
            int scoreDifference,
            String scoreLabel,
            List<String> reasons
    ) {
    }

    public record MeetingResponse(
            String id,
            String leadId,
            LocalDate meetingDate,
            String meetingTime,
            String meetingOutput,
            String aiSummary,
            Integer previousScore,
            Integer updatedScore,
            String scoreChangeReason,
            String aiModelVersion,
            String recordedById,
            OffsetDateTime createdAt
    ) {
        public static MeetingResponse from(LeadMeeting m) {
            return new MeetingResponse(
                    String.valueOf(m.getId()),
                    String.valueOf(m.getLeadId()),
                    m.getMeetingDate(),
                    m.getMeetingTime(),
                    m.getMeetingOutput(),
                    m.getAiSummary(),
                    m.getPreviousScore(),
                    m.getUpdatedScore(),
                    m.getScoreChangeReason(),
                    m.getAiModelVersion(),
                    m.getRecordedById() == null ? null : String.valueOf(m.getRecordedById()),
                    m.getCreatedAt()
            );
        }
    }
}
