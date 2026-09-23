package com.techcrm.crm.proposal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProposalLineItemRepository extends JpaRepository<ProposalLineItem, Long> {

    List<ProposalLineItem> findByProposalIdOrderByLineNumberAsc(Long proposalId);
}
