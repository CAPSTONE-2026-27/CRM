package com.techcrm.crm.lead;

/** An existing lead that a candidate duplicates, and what made them match. */
public record DuplicateMatch(Lead lead, DuplicateField field) {
}
