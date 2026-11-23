package com.techcrm.crm.marketing.email;

import com.techcrm.crm.marketing.email.EmailSuppression.Reason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The organization's email suppression list: unsubscribes, hard bounces and
 * complaints. Checked when leads are evaluated, when a campaign is frozen
 * into messages, and again immediately before each send.
 */
@Service
public class SuppressionService {

    private final EmailSuppressionRepository repository;

    public SuppressionService(EmailSuppressionRepository repository) {
        this.repository = repository;
    }

    /** Lower-cased address → reason, for the given addresses (nulls and invalid ones ignored). */
    @Transactional(readOnly = true)
    public Map<String, Reason> lookup(Long organizationId, Collection<String> emails) {
        List<String> normalised = emails.stream().map(EmailSettings::normalise).filter(Objects::nonNull).distinct().toList();
        Map<String, Reason> out = new HashMap<>();
        if (normalised.isEmpty()) return out;
        for (int i = 0; i < normalised.size(); i += 1000) {
            for (EmailSuppression s : repository.findMatching(organizationId,
                    normalised.subList(i, Math.min(normalised.size(), i + 1000)))) {
                out.put(s.getEmail(), s.getReason());
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Reason reasonFor(Long organizationId, String email) {
        String e = EmailSettings.normalise(email);
        if (e == null) return null;
        return repository.findByOrganizationIdAndEmail(organizationId, e).map(EmailSuppression::getReason).orElse(null);
    }

    /** Adds the address; an existing entry keeps its first reason. Returns true when newly added. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean suppress(Long organizationId, String email, Reason reason, Long sourceMessageId) {
        String e = EmailSettings.normalise(email);
        if (e == null) return false;
        if (repository.findByOrganizationIdAndEmail(organizationId, e).isPresent()) return false;
        EmailSuppression s = new EmailSuppression();
        s.setOrganizationId(organizationId);
        s.setEmail(e);
        s.setReason(reason);
        s.setSourceMessageId(sourceMessageId);
        repository.save(s);
        return true;
    }

    public static String describe(Reason reason) {
        return switch (reason) {
            case UNSUBSCRIBED -> "unsubscribed";
            case HARD_BOUNCE -> "address hard-bounced";
            case COMPLAINT -> "marked a previous email as spam";
            case MANUAL -> "on the suppression list";
        };
    }
}
