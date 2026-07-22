package com.techcrm.crm.lead;

import java.util.List;

public record RowWarning(int row, List<String> missingFields) {
}
