package com.techcrm.crm.lead;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.user.Role;
import com.techcrm.crm.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A person deciding qualification, for the leads the scoring model never
 * reached.
 *
 * Those sit at PENDING — the default the entity ships with — and nothing else
 * moves them, so a lead left there is one nobody can act on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("qualifying a lead by hand")
class LeadQualifyTest {

    private static final Long ORG_ID = 8L;

    @Mock LeadRepository leadRepository;
    @Mock UserRepository userRepository;
    @Mock AiScoringClient aiScoringClient;
    @Mock ApplicationEventPublisher events;

    private LeadService service;

    @BeforeEach
    void setUp() {
        service = new LeadService(leadRepository, userRepository, aiScoringClient, events);
        when(leadRepository.save(any(Lead.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void marksAPendingLeadAsQualified() {
        Lead lead = pendingLead();

        var response = service.qualify(caller(), 43L, true, null);

        assertThat(lead.getQualificationStatus()).isEqualTo("QUALIFIED");
        assertThat(response.qualificationStatus()).isEqualTo("QUALIFIED");
    }

    @Test
    void marksAPendingLeadAsUnqualified() {
        Lead lead = pendingLead();

        service.qualify(caller(), 43L, false, null);

        assertThat(lead.getQualificationStatus()).isEqualTo("UNQUALIFIED");
    }

    /** Kept beside the model's own reasoning rather than replacing it, so a
     *  person's judgement is never mistaken for the model's. */
    @Test
    void recordsTheReasonWhenOneIsGiven() {
        Lead lead = pendingLead();

        service.qualify(caller(), 43L, true, "  Budget confirmed on the call  ");

        assertThat(lead.getQualificationReasoning()).isEqualTo("Budget confirmed on the call");
    }

    /** A decision without a reason is still a decision; whatever the model had
     *  said stays rather than being blanked. */
    @Test
    void leavesTheExistingReasoningWhenNoReasonIsGiven() {
        Lead lead = pendingLead();
        lead.setQualificationReasoning("Scored 82 on budget and timeline");

        service.qualify(caller(), 43L, true, "   ");

        assertThat(lead.getQualificationReasoning()).isEqualTo("Scored 82 on budget and timeline");
    }

    @Test
    void refusesALeadInAnotherOrganization() {
        when(leadRepository.findByIdAndOrganizationId(43L, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.qualify(caller(), 43L, true, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    private Lead pendingLead() {
        Lead lead = new Lead();
        lead.setId(43L);
        lead.setOrganizationId(ORG_ID);
        lead.setFullName("Aditya Patil");
        lead.setQualificationStatus("PENDING");
        when(leadRepository.findByIdAndOrganizationId(43L, ORG_ID)).thenReturn(Optional.of(lead));
        return lead;
    }

    /** A manager, who sees every lead in the organization. A representative is
     *  scoped to the leads assigned to them, which is a separate rule with its
     *  own tests. */
    private AuthenticatedUser caller() {
        return new AuthenticatedUser(9L, ORG_ID, Role.MANAGER, List.of(), false);
    }
}
