package com.techcrm.crm.lead;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("welcoming a new lead")
class LeadWelcomeBotTriggerTest {

    private static final Long ORG_ID = 8L;

    @Mock SapProcessTriggerClient sap;

    private SapProcessProperties properties;
    private LeadWelcomeBotTrigger trigger;

    @BeforeEach
    void setUp() {
        properties = new SapProcessProperties();
        properties.setLeadWelcomeDefinitionId("welcome-def");
        trigger = new LeadWelcomeBotTrigger(sap, properties);
        when(sap.startProcess(anyString(), any())).thenReturn("instance-1");
    }

    /** The four the process declares, lower case, as the platform stores and
     *  matches them. */
    @Test
    void sendsEveryKeyTheProcessDeclares() {
        trigger.onLeadCreated(created("rajesh@novatech.com"));

        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(eq("welcome-def"), context.capture());
        assertThat(context.getValue()).containsOnlyKeys("leadname", "leademail", "company", "source");
        assertThat(context.getValue()).containsEntry("leadname", "Rajesh Kumar");
        assertThat(context.getValue()).containsEntry("leademail", "rajesh@novatech.com");
        assertThat(context.getValue()).containsEntry("company", "NovaTech Industries");
        assertThat(context.getValue()).containsEntry("source", "Web form");
    }

    /** A lead typed in by hand or imported from a spreadsheet need not carry
     *  an address. There is nobody to welcome, and that is not a fault. */
    @Test
    void sendsNothingToALeadWithNoAddress() {
        trigger.onLeadCreated(created(null));

        verify(sap, never()).startProcess(anyString(), any());
    }

    @Test
    void sendsNothingToALeadWhoseAddressIsBlank() {
        trigger.onLeadCreated(created("   "));

        verify(sap, never()).startProcess(anyString(), any());
    }

    /**
     * Blank optional fields are sent empty rather than omitted.
     *
     * The platform validates the context against the whole declared set and
     * refuses one that is missing a property, so a lead with no company still
     * sends the key.
     */
    @Test
    void sendsEmptyStringsForFieldsTheLeadDoesNotHave() {
        trigger.onLeadCreated(new LeadCreated(ORG_ID, 43L, "Rajesh Kumar",
                "rajesh@novatech.com", null, null));

        var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sap).startProcess(anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("company", "");
        assertThat(context.getValue()).containsEntry("source", "");
    }

    /** Sending a welcome is deliberate. An installation that has not
     *  configured the bot sends nothing. */
    @Test
    void sendsNothingWhenNoBotIsConfigured() {
        properties.setLeadWelcomeDefinitionId("");

        trigger.onLeadCreated(created("rajesh@novatech.com"));

        verify(sap, never()).startProcess(anyString(), any());
    }

    /** The lead is saved either way; a platform that cannot be reached is a
     *  log line, not an exception thrown into a listener nobody is catching. */
    @Test
    void survivesAPlatformThatCannotBeReached() {
        when(sap.startProcess(anyString(), any())).thenReturn(null);

        assertThatCode(() -> trigger.onLeadCreated(created("rajesh@novatech.com")))
                .doesNotThrowAnyException();
    }

    private LeadCreated created(String email) {
        return new LeadCreated(ORG_ID, 43L, "Rajesh Kumar", email,
                "NovaTech Industries", "Web form");
    }
}
