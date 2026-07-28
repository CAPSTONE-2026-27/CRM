package com.techcrm.crm.lead;

import java.util.List;

public record LeadImportResult(int imported, List<RowWarning> warnings, List<FailedRow> failed) {
}
