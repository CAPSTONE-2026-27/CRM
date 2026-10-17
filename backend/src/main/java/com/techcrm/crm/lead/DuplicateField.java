package com.techcrm.crm.lead;

/**
 * Which field made a lead a duplicate, and whether that should refuse it.
 *
 * The distinction is the point. An email address or a phone number identifies
 * one person, so a second lead carrying either is that person being entered
 * twice — worth refusing. A name is not: two people genuinely share one, and
 * the Add Lead wizard has never blocked a representative re-entering a lead
 * they already know about. So a name match is surfaced and the representative
 * decides.
 */
public enum DuplicateField {

    /** One address, one person. Blocks. */
    EMAIL("email", true),

    /** One number, one person. Blocks. */
    PHONE("phone", true),

    /** Two people genuinely share a name. Warns only. */
    NAME_COMPANY("name+company", false);

    private final String label;
    private final boolean blocking;

    DuplicateField(String label, boolean blocking) {
        this.label = label;
        this.blocking = blocking;
    }

    public String label() {
        return label;
    }

    public boolean blocking() {
        return blocking;
    }
}
