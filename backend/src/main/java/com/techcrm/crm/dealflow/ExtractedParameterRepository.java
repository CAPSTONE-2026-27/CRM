package com.techcrm.crm.dealflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExtractedParameterRepository extends JpaRepository<ExtractedParameter, Long> {

    List<ExtractedParameter> findByAnalysisIdOrderByDisplayOrderAsc(Long analysisId);
}
