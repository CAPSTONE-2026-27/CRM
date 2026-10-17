package com.techcrm.crm.lead.validation;

import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.lead.DuplicateField;
import com.techcrm.crm.lead.DuplicateMatch;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.lead.LeadRequest;
import com.techcrm.crm.lead.LeadService;
import com.techcrm.crm.lead.validation.LeadValidationDtos.DuplicateCheckRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.ReportVerdictRequest;
import com.techcrm.crm.lead.validation.LeadValidationDtos.StartValidationRequest;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules this feature actually has to get right.
 *
 * Two of them matter more than the rest: a validation that cannot run must not
 * stop a lead being created, and a duplicate must not be reported as an error,
 * because the Add Lead form has never blocked on duplicates.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("lead validation")
class LeadValidationServiceTest {

    private static final Long ORG_ID = 8L;

    @Mock LeadValidationRunRepository runRepository;
    @Mock LeadRepository leadRepository;
    @Mock LeadService leadService;
    @Mock SapProcessTriggerClient sap;

    SapProcessProperties properties;
    LeadValidationService service;

    private static AuthenticatedUser caller() {
        return new AuthenticatedUser(1L, ORG_ID, Role.SALES_REP, List.of(), false);
    }

    private static LeadRequest lead() {
        return new LeadRequest("Rajesh Kumar", "NovaTech Industries", null, null,
                "rajesh@novatech.com", "+91 90000 00000", null, null, null, null,
                "Web form", "WEB_FORM", null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        properties = new SapProcessProperties();
        properties.setEnabled(true);
        properties.setTokenUrl("https://token");
        properties.setClientId("id");
        properties.setClientSecret("secret");
        properties.setApiBaseUrl("https://api");
        properties.setLeadValidationDefinitionId("leadValidation");

        service = new LeadValidationService(runRepository, leadRepository, leadService, sap, properties);

        when(runRepository.save(any(LeadValidationRun.class))).thenAnswer(i -> i.getArgument(0));
        when(sap.isConfigured()).thenReturn(true);
    }

    /** The run as the repository would hand it back after start(). */
    private LeadValidationRun runningFor(UUID id) {
        LeadValidationRun run = new LeadValidationRun();
        run.setId(id);
        run.setOrganizationId(ORG_ID);
        run.setInstanceId("instance-1");
        run.setState(ValidationState.RUNNING.name());
        run.setStep(ValidationStep.TRIGGERED.name());
        run.setStartedAt(OffsetDateTime.now());
        when(runRepository.findByIdAndOrganizationId(id, ORG_ID)).thenReturn(Optional.of(run));
        return run;
    }

    @Nested
    @DisplayName("starting a run")
    class Starting {

        @Test
        void recordsTheInstanceAndMovesToRunning() {
            when(sap.startProcess(eq("leadValidation"), any())).thenReturn("instance-1");

            UUID id = service.start(caller(), new StartValidationRequest(lead(), "WEB_FORM"));

            assertThat(id).isNotNull();
            verify(sap).startProcess(eq("leadValidation"), any());
        }

        /**
         * The integration is off by default, and that must not look like a
         * failure to the representative: the lead is simply not checked.
         */
        @Test
        /** This bot specifically. The platform may be perfectly reachable for
         *  the contract and proposal bots while this one has no definition id
         *  because it has not been deployed. */
        void passesWithAWarningWhenTheIntegrationIsNotConfigured() {
            properties.setLeadValidationDefinitionId("");

            UUID id = service.start(caller(), new StartValidationRequest(lead(), "WEB_FORM"));
            LeadValidationRun saved = savedRun();

            assertThat(saved.getState()).isEqualTo(ValidationState.FAILED.name());
            assertThat(verdictValid(saved)).isTrue();
            assertThat(warnings(saved)).isNotEmpty();
            verify(sap, never()).startProcess(anyString(), any());
            assertThat(id).isNotNull();
        }

        /** Same again when the platform is configured but unreachable. */
        @Test
        void passesWithAWarningWhenTheProcessCannotBeStarted() {
            when(sap.startProcess(anyString(), any())).thenReturn(null);

            service.start(caller(), new StartValidationRequest(lead(), "WEB_FORM"));
            LeadValidationRun saved = savedRun();

            assertThat(saved.getState()).isEqualTo(ValidationState.FAILED.name());
            assertThat(verdictValid(saved)).isTrue();
        }

        /** The capture method reaches the automation, because it decides which
         *  required-field rule applies. */
        @Test
        void sendsTheCaptureMethodToTheAutomation() {
            when(sap.startProcess(anyString(), any())).thenReturn("instance-1");

            service.start(caller(), new StartValidationRequest(lead(), "EMAIL_PARSING"));

            var context = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(sap).startProcess(anyString(), context.capture());
            // Lower case: the process declares its inputs that way and the
            // platform refuses a camel-cased key as a missing property.
            assertThat(context.getValue()).containsEntry("capturemethod", "EMAIL_PARSING");
            assertThat(context.getValue()).containsKey("validationid");
        }

        /** Every key the process declares, spelled as it declares them. A
         *  camel-cased one is rejected outright, so this is worth pinning. */
        @Test
        void sendsEveryInputTheProcessDeclares() {
            when(sap.startProcess(anyString(), any())).thenReturn("instance-1");

            service.start(caller(), new StartValidationRequest(lead(), "WEB_FORM"));

            var context = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(sap).startProcess(anyString(), context.capture());
            assertThat(context.getValue()).containsOnlyKeys(
                    "validationid", "capturemethod", "fullname", "company", "email",
                    "phone", "product", "employeecount", "productquantity",
                    "estimateddealvalue");
        }
    }

    @Nested
    @DisplayName("polling a run")
    class Polling {

        /** Within the budget there is nothing to do but wait: the automation
         *  reports its own verdict, and asking the platform costs a privilege
         *  the service key may not have. */
        @Test
        void waitsForTheAutomationWithoutAskingThePlatform() {
            UUID id = UUID.randomUUID();
            runningFor(id);

            var status = service.status(caller(), id);

            assertThat(status.state()).isEqualTo(ValidationState.RUNNING.name());
            assertThat(status.verdict()).isNull();
            assertThat(status.label()).isNotBlank();
            verify(sap, never()).instanceStatus(anyString());
            verify(sap, never()).instanceContext(anyString());
        }

        /** Once the budget has expired the automation is not going to report,
         *  so the platform is worth asking — where it will answer. */
        @Test
        void asksThePlatformOnceTheBudgetHasExpired() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);
            run.setStartedAt(OffsetDateTime.now().minusSeconds(120));
            properties.setValidationTimeout(Duration.ofSeconds(20));
            when(sap.instanceStatus("instance-1"))
                    .thenReturn(new SapProcessTriggerClient.InstanceStatus("COMPLETED", true, true));
            when(sap.instanceContext("instance-1")).thenReturn(Map.of(
                    "custom", Map.of(
                            "valid", false,
                            "errors", "Email address is not valid: notanemail",
                            "warnings", "")));

            var status = service.status(caller(), id);

            assertThat(status.state()).isEqualTo(ValidationState.COMPLETED.name());
            assertThat(status.verdict().errors())
                    .containsExactly("Email address is not valid: notanemail");
        }

        /** Reading an instance needs a privilege that starting one does not.
         *  A service key without it is a normal configuration, and the run
         *  still has to end. */
        @Test
        void timesOutWhenThePlatformWillNotAnswer() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);
            run.setStartedAt(OffsetDateTime.now().minusSeconds(120));
            properties.setValidationTimeout(Duration.ofSeconds(20));
            when(sap.instanceStatus("instance-1")).thenReturn(null);

            var status = service.status(caller(), id);

            assertThat(status.state()).isEqualTo(ValidationState.TIMED_OUT.name());
            assertThat(status.verdict().valid()).isTrue();
            assertThat(status.verdict().warnings()).isNotEmpty();
        }

        /**
         * The budget exists so that a slow validation service cannot stop a
         * representative capturing a lead. It expires as a pass, not a refusal.
         */
        @Test
        void timesOutAsAPassWithAWarning() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);
            run.setStartedAt(OffsetDateTime.now().minusSeconds(120));
            properties.setValidationTimeout(Duration.ofSeconds(20));

            var status = service.status(caller(), id);

            assertThat(status.state()).isEqualTo(ValidationState.TIMED_OUT.name());
            assertThat(status.verdict().valid()).isTrue();
            assertThat(status.verdict().warnings()).isNotEmpty();
        }

        /** An instance that ends any way but COMPLETED is a degraded run. */
        @Test
        void treatsAnErroneousProcessAsUncheckedRatherThanRefused() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);
            run.setStartedAt(OffsetDateTime.now().minusSeconds(120));
            properties.setValidationTimeout(Duration.ofSeconds(20));
            when(sap.instanceStatus("instance-1"))
                    .thenReturn(new SapProcessTriggerClient.InstanceStatus("ERRONEOUS", true, false));

            var status = service.status(caller(), id);

            assertThat(status.state()).isEqualTo(ValidationState.FAILED.name());
            assertThat(status.verdict().valid()).isTrue();
        }

        /**
         * An empty context from a completed instance means the automation's
         * outputs were never mapped to process context variables. That is our
         * configuration fault, not the customer's, so the lead passes.
         */
        @Test
        void treatsAnEmptyContextAsUncheckedRatherThanRefused() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);
            run.setStartedAt(OffsetDateTime.now().minusSeconds(120));
            properties.setValidationTimeout(Duration.ofSeconds(20));
            when(sap.instanceStatus("instance-1"))
                    .thenReturn(new SapProcessTriggerClient.InstanceStatus("COMPLETED", true, true));
            when(sap.instanceContext("instance-1")).thenReturn(Map.of());

            var status = service.status(caller(), id);

            assertThat(status.verdict().valid()).isTrue();
            assertThat(status.verdict().warnings()).isNotEmpty();
        }

        /** A verdict claiming validity while listing errors is a bug in the
         *  automation; the reading that surfaces the errors wins. */
        @Test
        void errorsOverrideAnAutomationClaimingValidity() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);
            run.setStartedAt(OffsetDateTime.now().minusSeconds(120));
            properties.setValidationTimeout(Duration.ofSeconds(20));
            when(sap.instanceStatus("instance-1"))
                    .thenReturn(new SapProcessTriggerClient.InstanceStatus("COMPLETED", true, true));
            when(sap.instanceContext("instance-1")).thenReturn(Map.of(
                    "valid", true,
                    "errors", List.of("Email domain does not exist")));

            var status = service.status(caller(), id);

            assertThat(status.verdict().valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("duplicate check")
    class Duplicates {

        @Test
        void reportsNoDuplicateWhenTheLeadIsNew() {
            when(leadService.findDuplicate(anyLong(), any())).thenReturn(Optional.empty());

            var response = service.duplicateCheck(caller(),
                    new DuplicateCheckRequest("Rajesh Kumar", "rajesh@novatech.com", "NovaTech", "+91 90000 00000", null));

            assertThat(response.duplicate()).isFalse();
            assertThat(response.matchedLeadId()).isNull();
            assertThat(response.blocking()).isFalse();
        }

        /** An address identifies one person, so a second lead carrying it is
         *  that person entered twice — the automation turns this into an error. */
        @Test
        void marksAnEmailMatchAsBlocking() {
            when(leadService.findDuplicate(anyLong(), any()))
                    .thenReturn(Optional.of(new DuplicateMatch(leadWithId(17L), DuplicateField.EMAIL)));

            var response = service.duplicateCheck(caller(),
                    new DuplicateCheckRequest("Rajesh Kumar", "rajesh@novatech.com", "NovaTech", null, null));

            assertThat(response.duplicate()).isTrue();
            assertThat(response.matchedLeadId()).isEqualTo("17");
            assertThat(response.matchedOn()).isEqualTo("email");
            assertThat(response.blocking()).isTrue();
        }

        /** As does a phone number. */
        @Test
        void marksAPhoneMatchAsBlocking() {
            when(leadService.findDuplicate(anyLong(), any()))
                    .thenReturn(Optional.of(new DuplicateMatch(leadWithId(23L), DuplicateField.PHONE)));

            var response = service.duplicateCheck(caller(),
                    new DuplicateCheckRequest("Rajesh Kumar", null, null, "+91 90000 00000", null));

            assertThat(response.matchedOn()).isEqualTo("phone");
            assertThat(response.blocking()).isTrue();
        }

        /** Two people genuinely share a name, so this is surfaced and the
         *  representative decides — it never refuses the lead. */
        @Test
        void leavesANameMatchNonBlocking() {
            when(leadService.findDuplicate(anyLong(), any()))
                    .thenReturn(Optional.of(new DuplicateMatch(leadWithId(41L), DuplicateField.NAME_COMPANY)));

            var response = service.duplicateCheck(caller(),
                    new DuplicateCheckRequest("Rajesh Kumar", null, "NovaTech", null, null));

            assertThat(response.duplicate()).isTrue();
            assertThat(response.matchedOn()).isEqualTo("name+company");
            assertThat(response.blocking()).isFalse();
        }

        /** The automation passing its run id turns this call into a progress
         *  signal, so the browser's bar moves without the bot reporting. */
        @Test
        void recordsTheDuplicateStepWhenTheAutomationPassesItsRunId() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);
            when(leadService.findDuplicate(anyLong(), any())).thenReturn(Optional.empty());

            service.duplicateCheck(caller(),
                    new DuplicateCheckRequest("Rajesh Kumar", "rajesh@novatech.com", null, null, id.toString()));

            assertThat(run.getStep()).isEqualTo(ValidationStep.DUPLICATE_CHECK.name());
        }

        /** A malformed run id must not fail the duplicate answer the automation
         *  is waiting on. */
        @Test
        void ignoresAnUnparseableRunId() {
            when(leadService.findDuplicate(anyLong(), any())).thenReturn(Optional.empty());

            var response = service.duplicateCheck(caller(),
                    new DuplicateCheckRequest("Rajesh Kumar", "rajesh@novatech.com", null, null, "not-a-uuid"));

            assertThat(response.duplicate()).isFalse();
        }

        private Lead leadWithId(Long id) {
            Lead lead = new Lead();
            lead.setId(id);
            return lead;
        }
    }

    @Nested
    @DisplayName("the verdict reported by the automation")
    class ReportedVerdict {

        @Test
        void finishesTheRunWithWhatTheAutomationConcluded() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);

            service.recordVerdict(caller(), id,
                    new ReportVerdictRequest(false, "Email address is not valid: notanemail", "", ""));

            assertThat(run.getState()).isEqualTo(ValidationState.COMPLETED.name());
            var status = service.status(caller(), id);
            assertThat(status.verdict().valid()).isFalse();
            assertThat(status.verdict().errors())
                    .containsExactly("Email address is not valid: notanemail");
            assertThat(status.percent()).isEqualTo(100);
        }

        /** Several of each, joined the way the automation joins them. */
        @Test
        void splitsTheListsTheAutomationSends() {
            UUID id = UUID.randomUUID();
            runningFor(id);

            service.recordVerdict(caller(), id, new ReportVerdictRequest(
                    false,
                    "Missing required field: phone;Email address is not valid: bad",
                    "Already in the CRM as lead 41 (matched on name+company)",
                    "41"));

            var status = service.status(caller(), id);
            assertThat(status.verdict().errors()).hasSize(2);
            assertThat(status.verdict().warnings()).hasSize(1);
            assertThat(status.verdict().duplicateOfLeadId()).isEqualTo(41L);
        }

        /** A report claiming validity while listing errors is a bug in the
         *  automation. The safer reading is the one a person sees. */
        @Test
        void letsErrorsOverrideAReportClaimingValidity() {
            UUID id = UUID.randomUUID();
            runningFor(id);

            service.recordVerdict(caller(), id,
                    new ReportVerdictRequest(true, "Missing required field: phone", "", ""));

            assertThat(service.status(caller(), id).verdict().valid()).isFalse();
        }

        /** Warnings alone never refuse a lead. */
        @Test
        void passesALeadCarryingOnlyWarnings() {
            UUID id = UUID.randomUUID();
            runningFor(id);

            service.recordVerdict(caller(), id, new ReportVerdictRequest(
                    true, "", "Already in the CRM as lead 41 (matched on name+company)", "41"));

            var status = service.status(caller(), id);
            assertThat(status.verdict().valid()).isTrue();
            assertThat(status.verdict().warnings()).hasSize(1);
        }

        /** A retried report is the same report. Finishing twice would replace
         *  a verdict the representative may already be looking at. */
        @Test
        void ignoresASecondReportForTheSameRun() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);

            service.recordVerdict(caller(), id, new ReportVerdictRequest(false, "Missing required field: phone", "", ""));
            service.recordVerdict(caller(), id, new ReportVerdictRequest(true, "", "", ""));

            assertThat(run.getState()).isEqualTo(ValidationState.COMPLETED.name());
            assertThat(service.status(caller(), id).verdict().valid()).isFalse();
        }

        /** A report for a run this organization does not have is ignored, not
         *  refused — the automation could do nothing useful with an error. */
        @Test
        void ignoresAReportForAnUnknownRun() {
            UUID id = UUID.randomUUID();
            when(runRepository.findByIdAndOrganizationId(id, ORG_ID)).thenReturn(Optional.empty());

            service.recordVerdict(caller(), id, new ReportVerdictRequest(true, "", "", ""));

            verify(runRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("progress reported by the automation")
    class Progress {

        /** Field validation happens inside the automation and makes no call
         *  this side could observe, so the automation announces it. */
        @Test
        void advancesToTheStepTheAutomationNames() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);

            service.recordProgress(caller(), id.toString(), "FIELD_CHECK");

            assertThat(run.getStep()).isEqualTo(ValidationStep.FIELD_CHECK.name());
        }

        /** A bot from a newer project version may name a step this build has
         *  never heard of. Failing a validation over a label would be absurd. */
        @Test
        void ignoresAnUnrecognisedStep() {
            UUID id = UUID.randomUUID();
            LeadValidationRun run = runningFor(id);
            run.setStep(ValidationStep.TRIGGERED.name());

            service.recordProgress(caller(), id.toString(), "SOMETHING_NEW");

            assertThat(run.getStep()).isEqualTo(ValidationStep.TRIGGERED.name());
        }
    }

    /* ---------------------------------------------------------------- helpers */

    private LeadValidationRun savedRun() {
        var captor = org.mockito.ArgumentCaptor.forClass(LeadValidationRun.class);
        verify(runRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private boolean verdictValid(LeadValidationRun run) {
        return Boolean.TRUE.equals(run.getVerdict().get("valid"));
    }

    @SuppressWarnings("unchecked")
    private List<String> warnings(LeadValidationRun run) {
        return (List<String>) run.getVerdict().get("warnings");
    }
}
