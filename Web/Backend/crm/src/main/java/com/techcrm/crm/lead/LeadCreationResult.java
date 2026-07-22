package com.techcrm.crm.lead;

import java.util.List;

/** Wraps a created lead with which recommended fields came back empty —
 *  used by paths (paste-email) where that's expected/common, so the
 *  caller can surface it rather than the gap going unnoticed. */
public record LeadCreationResult(LeadResponse lead, List<String> missingFields) {
}
