package com.techcrm.crm.contract;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContractRepository extends JpaRepository<Contract, Long> {

    Optional<Contract> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Contract> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /** The deal's live contract, if it has one — the idempotency check behind
     *  POST /api/contracts/generate. At most one row can match, enforced by
     *  {@code uq_contracts_live_per_deal}. */
    @Query("""
            select c from Contract c
            where c.dealId = :dealId
              and c.organizationId = :organizationId
              and c.status in (com.techcrm.crm.contract.ContractStatus.GENERATED,
                               com.techcrm.crm.contract.ContractStatus.SENT,
                               com.techcrm.crm.contract.ContractStatus.VIEWED,
                               com.techcrm.crm.contract.ContractStatus.SIGNED)
            """)
    Optional<Contract> findLiveForDeal(@Param("dealId") Long dealId,
                                       @Param("organizationId") Long organizationId);

    Optional<Contract> findByContractNumber(String contractNumber);
}
