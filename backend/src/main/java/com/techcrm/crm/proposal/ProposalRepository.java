package com.techcrm.crm.proposal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProposalRepository extends JpaRepository<Proposal, Long> {

    Optional<Proposal> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Proposal> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /** The deal's live proposal, if it has one — the idempotency check behind
     *  POST /api/proposals/generate. At most one row can match, enforced by
     *  {@code uq_proposals_live_per_deal}. */
    @Query("""
            select p from Proposal p
            where p.dealId = :dealId
              and p.organizationId = :organizationId
              and p.status in (com.techcrm.crm.proposal.ProposalStatus.GENERATING,
                               com.techcrm.crm.proposal.ProposalStatus.GENERATED,
                               com.techcrm.crm.proposal.ProposalStatus.SENT_FOR_SIGNATURE,
                               com.techcrm.crm.proposal.ProposalStatus.VIEWED,
                               com.techcrm.crm.proposal.ProposalStatus.SIGNED)
            """)
    Optional<Proposal> findLiveForDeal(@Param("dealId") Long dealId,
                                       @Param("organizationId") Long organizationId);

    Optional<Proposal> findByProposalNumber(String proposalNumber);
}
