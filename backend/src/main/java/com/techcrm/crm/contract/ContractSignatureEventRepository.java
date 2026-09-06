package com.techcrm.crm.contract;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContractSignatureEventRepository extends JpaRepository<ContractSignatureEvent, Long> {

    boolean existsByContractIdAndEventTypeAndPayloadDigest(Long contractId, String eventType, String payloadDigest);

    List<ContractSignatureEvent> findByContractIdOrderByReceivedAtAsc(Long contractId);
}
