package com.techcrm.crm.contract.signature;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.contract.Contract;
import com.techcrm.crm.contract.ContractRecordService;
import com.techcrm.crm.contract.ContractRepository;
import com.techcrm.crm.contract.ContractStatus;
import com.techcrm.crm.deal.DealRepository;
import com.techcrm.crm.onboarding.CustomerOnboardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static com.techcrm.crm.contract.ContractFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The callback. Its two jobs are being unforgeable and being idempotent, and
 * both are load-bearing: this endpoint is public, and Documenso retries.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContractWebhookServiceTest {

    private static final String SECRET = "whsec-test-value";

    @Mock ContractRepository contractRepository;
    @Mock DealRepository dealRepository;
    @Mock ContractEventRecorder eventRecorder;
    @Mock ContractRecordService recordService;
    @Mock CustomerOnboardingService onboardingService;
    @Mock AuditLogService auditLogService;

    DocumensoProperties properties;
    ContractWebhookService service;

    @BeforeEach
    void setUp() {
        properties = new DocumensoProperties();
        properties.getWebhook().setSecret(SECRET);

        service = new ContractWebhookService(contractRepository, dealRepository, eventRecorder,
                recordService, onboardingService, auditLogService, properties);

        Contract sent = contract();
        sent.setStatus(ContractStatus.SENT);
        sent.setDocumensoDocumentId("881");

        when(contractRepository.findByDocumensoDocumentId("881")).thenReturn(Optional.of(sent));
        when(contractRepository.findByContractNumber("CTR-000042")).thenReturn(Optional.of(sent));
        when(contractRepository.findById(42L)).thenReturn(Optional.of(sent));
        when(contractRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dealRepository.findById(DEAL_ID)).thenReturn(Optional.of(deal()));
        when(eventRecorder.recordIfNew(anyLong(), anyString(), any(), anyString())).thenReturn(true);
    }

    private static String body(String event) {
        return """
                {"event":"%s","createdAt":"2026-09-05T10:00:00.000Z",
                 "payload":{"id":881,"externalId":"CTR-000042","status":"COMPLETED",
                            "recipients":[{"id":45,"email":"asha.menon@northwind.example"}]}}
                """.formatted(event);
    }

    @Nested
    class Authenticity {

        /** The endpoint is unauthenticated by necessity, so a wrong secret must
         *  be indistinguishable from no secret and must change nothing. */
        @Test
        void rejectsAWrongSecret() {
            assertThatThrownBy(() -> service.handle(body("DOCUMENT_COMPLETED"), "not-the-secret"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            verify(eventRecorder, never()).recordIfNew(anyLong(), anyString(), any(), anyString());
            verify(onboardingService, never()).initiate(any(), any(), any(), any(), any());
        }

        @Test
        void rejectsAMissingSecretHeader() {
            assertThatThrownBy(() -> service.handle(body("DOCUMENT_COMPLETED"), null))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        /**
         * Fails closed. An unconfigured secret on a public endpoint would let
         * anyone mark any contract signed, so the endpoint refuses rather than
         * trusting.
         */
        @Test
        void refusesEverythingWhenNoSecretIsConfigured() {
            properties.getWebhook().setSecret("");

            assertThatThrownBy(() -> service.handle(body("DOCUMENT_COMPLETED"), "anything"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        void rejectsAMalformedBody() {
            assertThatThrownBy(() -> service.handle("not json at all", SECRET))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void rejectsAPayloadWithNoEventName() {
            assertThatThrownBy(() -> service.handle("{\"payload\":{\"id\":881}}", SECRET))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void reportsAnEventForAContractItCannotFind() {
            when(contractRepository.findByDocumensoDocumentId(anyString())).thenReturn(Optional.empty());
            when(contractRepository.findByContractNumber(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.handle(body("DOCUMENT_COMPLETED"), SECRET))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        /** The document id can be absent if the send call died after Documenso
         *  accepted it; the contract number we sent as externalId still finds it. */
        @Test
        void fallsBackToTheExternalIdWhenTheDocumentIdIsUnknown() {
            when(contractRepository.findByDocumensoDocumentId("881")).thenReturn(Optional.empty());

            var ack = service.handle(body("DOCUMENT_COMPLETED"), SECRET);

            assertThat(ack.result()).isEqualTo("processed");
            verify(contractRepository).findByContractNumber("CTR-000042");
        }
    }

    @Nested
    class Outcomes {

        @Test
        void aCompletedDocumentSignsTheContractAndActivatesTheCustomer() {
            var ack = service.handle(body("DOCUMENT_COMPLETED"), SECRET);

            assertThat(ack.result()).isEqualTo("processed");
            assertThat(ack.status()).isEqualTo("SIGNED");

            var saved = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(ContractStatus.SIGNED);
            assertThat(saved.getValue().getSignedAt()).isNotNull();

            verify(recordService).mirrorOntoDeal(eq(DEAL_ID), eq(ContractStatus.SIGNED), any());
            verify(onboardingService).initiate(ORG_ID, DEAL_ID, "OPP-000031", ACCOUNT_ID, OWNER_ID);
        }

        @Test
        void anOpenedDocumentOnlyMarksItViewed() {
            var ack = service.handle(body("DOCUMENT_OPENED"), SECRET);

            assertThat(ack.status()).isEqualTo("VIEWED");
            verify(onboardingService, never()).initiate(any(), any(), any(), any(), any());
        }

        /** Activation is for signatures only. A declined contract must not open
         *  an onboarding. */
        @Test
        void aRejectedDocumentRecordsTheDeclineAndActivatesNobody() {
            String rejected = """
                    {"event":"DOCUMENT_REJECTED",
                     "payload":{"id":881,"externalId":"CTR-000042",
                                "recipients":[{"id":45,"rejectionReason":"Pricing not approved"}]}}
                    """;

            var ack = service.handle(rejected, SECRET);

            assertThat(ack.status()).isEqualTo("REJECTED");

            var saved = ArgumentCaptor.forClass(Contract.class);
            verify(contractRepository).save(saved.capture());
            assertThat(saved.getValue().getRejectionReason()).isEqualTo("Pricing not approved");
            assertThat(saved.getValue().getRejectedAt()).isNotNull();

            verify(onboardingService, never()).initiate(any(), any(), any(), any(), any());
        }

        @Test
        void aCancelledDocumentIsTreatedAsRejected() {
            assertThat(service.handle(body("DOCUMENT_CANCELLED"), SECRET).status()).isEqualTo("REJECTED");
        }

        /** A Documenso release that adds an event must not break the callback;
         *  it is recorded and changes nothing. */
        @Test
        void anUnrecognisedEventIsRecordedWithoutChangingTheStatus() {
            var ack = service.handle(body("DOCUMENT_TITLE_UPDATED"), SECRET);

            assertThat(ack.status()).isEqualTo("SENT");
            verify(contractRepository, never()).save(any());
            verify(auditLogService).record(eq(ORG_ID), eq(null), eq("CONTRACT_DOCUMENT_TITLE_UPDATED"),
                    eq("CONTRACT"), eq("42"), anyString());
        }

        /** A stray OPENED arriving after the signature must not walk a signed
         *  contract backwards. */
        @Test
        void doesNotMoveAContractOutOfATerminalStatus() {
            Contract signed = contract();
            signed.setStatus(ContractStatus.SIGNED);
            signed.setDocumensoDocumentId("881");
            when(contractRepository.findById(42L)).thenReturn(Optional.of(signed));
            when(contractRepository.findByDocumensoDocumentId("881")).thenReturn(Optional.of(signed));

            var ack = service.handle(body("DOCUMENT_OPENED"), SECRET);

            assertThat(ack.status()).isEqualTo("SIGNED");
            verify(contractRepository, never()).save(any());
        }

        @Test
        void signingTwiceDoesNotActivateTheCustomerTwice() {
            Contract signed = contract();
            signed.setStatus(ContractStatus.SIGNED);
            signed.setDocumensoDocumentId("881");
            when(contractRepository.findById(42L)).thenReturn(Optional.of(signed));
            when(contractRepository.findByDocumensoDocumentId("881")).thenReturn(Optional.of(signed));

            service.handle(body("DOCUMENT_COMPLETED"), SECRET);

            verify(onboardingService, never()).initiate(any(), any(), any(), any(), any());
        }
    }

    @Nested
    class Idempotency {

        /** The retry case. Documenso redelivers anything it did not get a 2xx
         *  for, and a second delivery must not open a second onboarding. */
        @Test
        void aRedeliveryIsAcknowledgedAndDoesNothing() {
            when(eventRecorder.recordIfNew(anyLong(), anyString(), any(), anyString())).thenReturn(false);

            var ack = service.handle(body("DOCUMENT_COMPLETED"), SECRET);

            assertThat(ack.result()).isEqualTo("duplicate");
            assertThat(ack.status()).isEqualTo("SENT");
            verify(contractRepository, never()).save(any());
            verify(onboardingService, never()).initiate(any(), any(), any(), any(), any());
            verify(recordService, never()).mirrorOntoDeal(anyLong(), any(), any());
        }

        /** The digest is taken over the raw body, so an identical redelivery
         *  collides and a genuinely different event does not. */
        @Test
        void identicalBodiesProduceTheSameDigestAndDifferentOnesDoNot() {
            var digest = ArgumentCaptor.forClass(String.class);

            service.handle(body("DOCUMENT_COMPLETED"), SECRET);
            service.handle(body("DOCUMENT_COMPLETED"), SECRET);
            service.handle(body("DOCUMENT_OPENED"), SECRET);

            verify(eventRecorder, org.mockito.Mockito.times(3))
                    .recordIfNew(anyLong(), anyString(), any(), digest.capture());

            var digests = digest.getAllValues();
            assertThat(digests.get(0)).isEqualTo(digests.get(1));
            assertThat(digests.get(0)).isNotEqualTo(digests.get(2));
            assertThat(digests.get(0)).hasSize(64);
        }
    }

    @Test
    void statusMappingIsExhaustiveAndTerminalSafe() {
        assertThat(ContractWebhookService.statusFor("DOCUMENT_SENT", ContractStatus.GENERATED))
                .isEqualTo(ContractStatus.SENT);
        assertThat(ContractWebhookService.statusFor("DOCUMENT_OPENED", ContractStatus.SENT))
                .isEqualTo(ContractStatus.VIEWED);
        assertThat(ContractWebhookService.statusFor("DOCUMENT_SIGNED", ContractStatus.VIEWED))
                .isEqualTo(ContractStatus.SIGNED);
        assertThat(ContractWebhookService.statusFor("DOCUMENT_COMPLETED", ContractStatus.VIEWED))
                .isEqualTo(ContractStatus.SIGNED);
        assertThat(ContractWebhookService.statusFor("DOCUMENT_REJECTED", ContractStatus.SENT))
                .isEqualTo(ContractStatus.REJECTED);
        assertThat(ContractWebhookService.statusFor("DOCUMENT_CREATED", ContractStatus.GENERATED)).isNull();
        assertThat(ContractWebhookService.statusFor("DOCUMENT_COMPLETED", ContractStatus.REJECTED)).isNull();
        assertThat(ContractWebhookService.statusFor("DOCUMENT_OPENED", ContractStatus.SIGNED)).isNull();
    }
}
