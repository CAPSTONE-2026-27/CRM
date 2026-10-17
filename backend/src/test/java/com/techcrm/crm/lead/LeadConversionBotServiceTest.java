package com.techcrm.crm.lead;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.sap.SapProcessProperties;
import com.techcrm.crm.sap.SapProcessTriggerClient;
import com.techcrm.crm.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("converting a lead through the opportunity bot")
class LeadConversionBotServiceTest {

    private static final Long ORG_ID = 8L;

    @Mock LeadRepository leadRepository;
    @Mock SapProcessTriggerClient sap;

    private SapProcessProperties properties;
    private LeadConversionBotService service;

    @BeforeEach
    void setUp() {
        properties = new SapProcessProperties();
        properties.setEnabled(true);
        properties.setTokenUrl("https://token");
        properties.setClientId("id");
        properties.setClientSecret("secret");
        properties.setApiBaseUrl("https://api");
        properties.setOpportunityDefinitionId("opportunity-def");

        service = new LeadConversionBotService(leadRepository, sap, properties);
        when(sap.isConfigured()).thenReturn(true);
        when(sap.startProcess(anyString(), any())).thenReturn("instance-1");
    }

    @Nested
    @DisplayName("deciding whether a lead is ready")
    class Readiness {

        @Test
        void startsTheBotForALeadTheModelCalledHot() {
            leadIs("HOT", null, "QUALIFIED");

            service.requestConversion(caller(), 43L);

            verify(sap).startProcess(eq("opportunity-def"), eq(Map.of("leadid", "43")));
        }

        /** A warm lead is worth a meeting, not an opportunity. The message has
         *  to say so, or a representative is left with a card that will not
         *  move and no idea why. */
        @Test
        void refusesALeadTheModelCalledWarm() {
            leadIs("WARM", null, "QUALIFIED");

            assertThatThrownBy(() -> service.requestConversion(caller(), 43L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("WARM")
                    .hasMessageContaining("meeting");

            verify(sap, never()).startProcess(anyString(), any());
        }

        @Test
        void refusesALeadTheModelCalledCold() {
            leadIs("COLD", null, "QUALIFIED");

            assertThatThrownBy(() -> service.requestConversion(caller(), 43L))
                    .isInstanceOf(ResponseStatusException.class);
        }

        /**
         * No verdict is not a refusal.
         *
         * A scoring service that is down, or a label this build does not know,
         * must not make leads permanently unconvertible — that would hand an
         * outage the power to stop the pipeline.
         */
        @Test
        void convertsAnUnscoredLead() {
            leadIs(null, null, "QUALIFIED");

            assertThatCode(() -> service.requestConversion(caller(), 43L)).doesNotThrowAnyException();
            verify(sap).startProcess(anyString(), any());
        }

        @Test
        void convertsALeadWhoseLabelThisBuildDoesNotKnow() {
            leadIs("PROMISING", null, "QUALIFIED");

            assertThatCode(() -> service.requestConversion(caller(), 43L)).doesNotThrowAnyException();
        }

        /** Case and spacing are display concerns; the verdict is the same. */
        @Test
        void readsTheLabelWhateverItsCasing() {
            leadIs("  hot  ", null, "QUALIFIED");

            assertThatCode(() -> service.requestConversion(caller(), 43L)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the gates the conversion endpoint also enforces")
    class ExistingGates {

        @Test
        void refusesALeadAlreadyConverted() {
            leadIs("HOT", 77L, "QUALIFIED");

            assertThatThrownBy(() -> service.requestConversion(caller(), 43L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("already been converted");

            verify(sap, never()).startProcess(anyString(), any());
        }

        @Test
        void refusesAnUnqualifiedLead() {
            leadIs("HOT", null, "UNQUALIFIED");

            assertThatThrownBy(() -> service.requestConversion(caller(), 43L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("qualified");
        }

        @Test
        void refusesALeadInAnotherOrganization() {
            when(leadRepository.findByIdAndOrganizationId(43L, ORG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestConversion(caller(), 43L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("when no automation is wired up")
    class NotConfigured {

        @Test
        void saysSoRatherThanPretendingOtherwise() {
            properties.setOpportunityDefinitionId("");

            assertThat(service.isConfigured()).isFalse();
        }

        /** The readiness judgement is the same whoever does the converting. */
        @Test
        void stillRefusesALeadThatIsNotReady() {
            properties.setOpportunityDefinitionId("");
            leadIs("COLD", null, "QUALIFIED");

            assertThatThrownBy(() -> service.requireReadyToConvert(caller(), 43L))
                    .isInstanceOf(ResponseStatusException.class);
        }

        /** The deal has not moved and the lead is unharmed; a platform that
         *  cannot be reached is a log line, not an exception. */
        @Test
        void survivesAPlatformThatCannotBeReached() {
            when(sap.startProcess(anyString(), any())).thenReturn(null);
            leadIs("HOT", null, "QUALIFIED");

            assertThatCode(() -> service.requestConversion(caller(), 43L)).doesNotThrowAnyException();
        }
    }

    /* ---------------------------------------------------------------- helpers */

    private void leadIs(String label, Long convertedDealId, String qualificationStatus) {
        Lead lead = new Lead();
        lead.setId(43L);
        lead.setOrganizationId(ORG_ID);
        lead.setFullName("Rajesh Kumar");
        lead.setAiScoreLabel(label);
        lead.setConvertedDealId(convertedDealId);
        lead.setQualificationStatus(qualificationStatus);
        when(leadRepository.findByIdAndOrganizationId(43L, ORG_ID)).thenReturn(Optional.of(lead));
    }

    private AuthenticatedUser caller() {
        return new AuthenticatedUser(9L, ORG_ID, Role.SALES_REP, List.of(), false);
    }
}
