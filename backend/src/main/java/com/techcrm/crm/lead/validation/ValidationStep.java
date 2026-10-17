package com.techcrm.crm.lead.validation;

/**
 * The furthest milestone observed in a validation run.
 *
 * Mostly <em>observed</em> rather than reported. The backend infers these from
 * what it can see — the process being started, the automation asking whether
 * the lead is a duplicate, and finally the instance completing — so the bot
 * carries almost no reporting code.
 *
 * {@link #FIELD_CHECK} is the exception. Field validation happens entirely
 * inside the automation and makes no call this side could observe, so the
 * automation announces it. Without that the bar would sit at 25% for the whole
 * of the field checks and then jump, which reads as a stall.
 *
 * The percentages are fixed rather than predicted. The backend knows which step
 * it is on, not how long the step will take, and a bar that jumps between known
 * positions is more honest than one that invents a rate.
 */
public enum ValidationStep {

    STARTING("Starting validation…", 10),
    TRIGGERED("Connected to validation service", 25),
    FIELD_CHECK("Checking lead details…", 45),
    DUPLICATE_CHECK("Checking for duplicates…", 70),
    DONE("Done", 100);

    private final String label;
    private final int percent;

    ValidationStep(String label, int percent) {
        this.label = label;
        this.percent = percent;
    }

    public String label() {
        return label;
    }

    public int percent() {
        return percent;
    }

    /** Never moves backwards. The automation's calls can arrive out of order —
     *  a retried duplicate check after the external check, for instance — and a
     *  progress bar that goes backwards reads as a fault. */
    public static ValidationStep furthest(ValidationStep current, ValidationStep candidate) {
        if (current == null) {
            return candidate;
        }
        return candidate.ordinal() > current.ordinal() ? candidate : current;
    }

    public static ValidationStep parse(String name) {
        try {
            return name == null ? STARTING : valueOf(name);
        } catch (IllegalArgumentException e) {
            return STARTING;
        }
    }
}
