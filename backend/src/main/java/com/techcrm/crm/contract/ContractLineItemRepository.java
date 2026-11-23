package com.techcrm.crm.contract;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContractLineItemRepository extends JpaRepository<ContractLineItem, Long> {

    List<ContractLineItem> findByContractIdOrderByLineNumberAsc(Long contractId);

    void deleteByContractId(Long contractId);
}
