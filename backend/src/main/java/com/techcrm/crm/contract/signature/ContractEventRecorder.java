package com.techcrm.crm.contract.signature;

import com.techcrm.crm.contract.ContractSignatureEvent;
import com.techcrm.crm.contract.ContractSignatureEventRepository;
import org.springframework.stereotype.Service;

/**
 * The idempotency gate for Documenso webhooks.
 *
 * Documenso retries a delivery it did not get a 2xx for, so the same event
 * arrives more than once. Everything downstream of this — the status change, the
 * audit entry, customer activation — runs only when this says the delivery is
 * new.
 *
 * Deliberately no transaction of its own. It runs inside the caller's, so the
 * "we have seen this" marker commits with the actions it guards and rolls back
 * with them: recorded-but-not-applied would make Documenso's retry look like a
 * duplicate and lose the signature entirely.
 *
 * The read-then-write is not itself atomic, which two simultaneous redeliveries
 * could slip through. {@code uq_contract_signature_events} is what actually
 * prevents that — the loser's insert violates it and its whole transaction rolls
 * back, having done nothing, and Documenso's next retry sees the committed row
 * and is answered as the duplicate it is.
 */
@Service
public class ContractEventRecorder {

    private final ContractSignatureEventRepository eventRepository;

    public ContractEventRecorder(ContractSignatureEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** @return true when this delivery had not been seen before and should be acted on. */
    public boolean recordIfNew(Long contractId, String eventType, String documensoDocumentId, String payloadDigest) {
        if (eventRepository.existsByContractIdAndEventTypeAndPayloadDigest(contractId, eventType, payloadDigest)) {
            return false;
        }

        ContractSignatureEvent event = new ContractSignatureEvent();
        event.setContractId(contractId);
        event.setEventType(eventType);
        event.setDocumensoDocumentId(documensoDocumentId);
        event.setPayloadDigest(payloadDigest);
        eventRepository.save(event);
        return true;
    }
}
