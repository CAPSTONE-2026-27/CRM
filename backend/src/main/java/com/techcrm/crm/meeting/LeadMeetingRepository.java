package com.techcrm.crm.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeadMeetingRepository extends JpaRepository<LeadMeeting, Long> {

    List<LeadMeeting> findByLeadIdAndOrganizationIdOrderByMeetingDateDescCreatedAtDesc(
            Long leadId, Long organizationId);

    /** How many meetings this lead already has, so a preview can label itself
     *  "Meeting 3" before the row it describes exists. */
    long countByLeadIdAndOrganizationId(Long leadId, Long organizationId);
}
