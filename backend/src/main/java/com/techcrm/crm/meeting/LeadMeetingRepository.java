package com.techcrm.crm.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeadMeetingRepository extends JpaRepository<LeadMeeting, Long> {

    List<LeadMeeting> findByLeadIdAndOrganizationIdOrderByMeetingDateDescCreatedAtDesc(
            Long leadId, Long organizationId);
}
