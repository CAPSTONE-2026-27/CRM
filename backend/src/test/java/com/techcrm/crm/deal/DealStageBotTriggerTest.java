package com.techcrm.crm.deal;

import com.techcrm.crm.sap.SapProcessProperties;
import com.techcrm.crm.sap.SapProcessTriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("starting a document bot from a stage change")
class DealStageBotTriggerTest {

    private static final Long ORG_ID = 8L;

    @Mock SapProcessTriggerClient sap;

    private SapProcessProperties properties;
    private DealStageBotTrigger trigger;

    @BeforeEach
    void setUp() {
        properties = new SapProcessProperties();
        properties.setProposalDefinitionId("proposal-def");
        properties.setContractDefinitionId("contract-def");
        trigger = new DealStageBotTrigger(sap, properties);
        when(sap.startProcess(anyString(), any())).thenReturn("instance-1");
    }

    @Test
    void startsTheContractBotWhenADealReachesContract() {
        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.CONTRACT));

        verify(sap).startProcess(eq("contract-def"), eq(Map.of(
                "dealid", "43", "recipientname", "", "recipientemail", "")));
    }

    @Test
    void startsTheProposalBotWhenADealReachesProposal() {
        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.PROPOSAL));

        verify(sap).startProcess(eq("proposal-def"), any());
    }

    /** Most stages have no document to produce. */
    @Test
    void startsNothingForAStageNoBotAnswers() {
        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.NEGOTIATION));

        verify(sap, never()).startProcess(anyString(), any());
    }

    /** Wiring a bot to a stage is deliberate. An installation that has not done
     *  it generates the document by hand, and nothing here fires. */
    @Test
    void startsNothingWhenTheBotIsNotConfigured() {
        properties.setContractDefinitionId("");

        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.CONTRACT));

        verify(sap, never()).startProcess(anyString(), any());
    }

    /** The deal has already moved. A platform that cannot be reached is worth a
     *  log line, not an exception thrown into a listener nobody is catching. */
    @Test
    void survivesAPlatformThatCannotBeReached() {
        when(sap.startProcess(anyString(), any())).thenReturn(null);

        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.CONTRACT));

        verify(sap).startProcess(eq("contract-def"), any());
    }

    /**
     * Every declared input, lower case, including the ones left empty.
     *
     * The platform validates the context against the whole set and refuses one
     * that is missing a property rather than defaulting it, so an omitted
     * recipient is a refused start — not a start that falls back.
     */
    @Test
    void sendsEveryKeyTheProcessDeclares() {
        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.CONTRACT));

        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(anyString(), context.capture());
        assertThat(context.getValue())
                .containsOnlyKeys("dealid", "recipientname", "recipientemail");
        assertThat(context.getValue()).containsEntry("dealid", "43");
    }

    /**
     * The key names belong to the deployed process, not to this code.
     *
     * A process that spells its recipient inputs differently is still a
     * working process — the platform matches the declared names exactly — so
     * each bot's are read from configuration rather than assumed.
     */
    @Test
    void usesTheKeyNamesEachProcessDeclares() {
        properties.setProposalRecipientNameKey("ricipientname");
        properties.setProposalRecipientEmailKey("ricipientemail");

        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.PROPOSAL));

        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(anyString(), context.capture());
        assertThat(context.getValue())
                .containsOnlyKeys("dealid", "ricipientname", "ricipientemail");
    }

    /** One bot's spelling does not leak into the other's. */
    @Test
    void keepsEachBotsKeyNamesToItself() {
        properties.setProposalRecipientNameKey("ricipientname");
        properties.setProposalRecipientEmailKey("ricipientemail");

        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.CONTRACT));

        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(anyString(), context.capture());
        assertThat(context.getValue())
                .containsOnlyKeys("dealid", "recipientname", "recipientemail");
    }

    /**
     * Blank recipients, so the automation addresses the document to the
     * account's own contact. The overrides exist for a person testing by hand;
     * a run nobody asked for has no business redirecting a customer's contract.
     */
    @Test
    void leavesTheRecipientToTheAccountsOwnContact() {
        trigger.onStageChanged(new DealStageChanged(ORG_ID, 43L, DealStages.CONTRACT));

        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("recipientname", "");
        assertThat(context.getValue()).containsEntry("recipientemail", "");
    }
}
