package com.techcrm.crm.meeting;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.meeting.MeetingDtos.MeetingAnalysisResponse;
import com.techcrm.crm.meeting.MeetingDtos.MeetingInput;
import com.techcrm.crm.meeting.MeetingDtos.MeetingResponse;
import com.techcrm.crm.meeting.MeetingDtos.SaveMeetingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Lead Output module — meeting records logged against a lead. */
@RestController
@RequestMapping("/api/leads/{leadId}/meetings")
public class LeadMeetingController {

    private final LeadMeetingService meetingService;
    private final MeetingSchedulerBotService schedulerBotService;

    public LeadMeetingController(LeadMeetingService meetingService,
                                 MeetingSchedulerBotService schedulerBotService) {
        this.meetingService = meetingService;
        this.schedulerBotService = schedulerBotService;
    }

    /**
     * Books a meeting and has the automation invite the lead to it.
     *
     * <b>202</b> with where the invitation went. Nothing about the meeting's
     * outcome is recorded here — this is a meeting that has not happened yet,
     * and what came of it is saved separately once it has.
     *
     * <b>409</b> when the lead has no email address, since there would be
     * nobody to invite.
     */
    @PostMapping("/schedule")
    public ResponseEntity<MeetingDtos.ScheduleMeetingResponse> schedule(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable Long leadId,
            @Valid @RequestBody MeetingDtos.ScheduleMeetingRequest request) {

        return ResponseEntity.accepted().body(schedulerBotService.schedule(caller, leadId, request));
    }

    @GetMapping
    public List<MeetingResponse> history(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long leadId) {
        return meetingService.history(caller, leadId);
    }

    /** Generates the summary and re-score for review. Persists nothing. */
    @PostMapping("/analyze")
    public MeetingAnalysisResponse analyze(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable Long leadId,
                                           @Valid @RequestBody MeetingInput input) {
        return meetingService.analyze(caller, leadId, input);
    }

    @PostMapping
    public ResponseEntity<MeetingResponse> save(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @PathVariable Long leadId,
                                                @Valid @RequestBody SaveMeetingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(meetingService.save(caller, leadId, request));
    }
}
