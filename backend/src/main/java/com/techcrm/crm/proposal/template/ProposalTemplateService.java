package com.techcrm.crm.proposal.template;

import com.techcrm.crm.proposal.ProposalAssembly;
import com.techcrm.crm.proposal.ProposalProperties;
import com.techcrm.crm.proposal.ProposalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses a proposal template and loads its bytes.
 *
 * The choice lives here rather than in the controller, so adding a fourth
 * template is one branch in one method. Templates themselves are ordinary .docx
 * files — editable in Word by whoever owns the proposal wording, without
 * touching Java.
 */
@Service
public class ProposalTemplateService {

    private static final Logger log = LoggerFactory.getLogger(ProposalTemplateService.class);

    private final ProposalProperties properties;
    private final ResourceLoader resourceLoader;

    /**
     * Templates change about once a quarter and are a few tens of kilobytes, so
     * they are read once and kept. Keyed by resolved location rather than by
     * type, so pointing a type at a different file through configuration
     * naturally misses the cache instead of serving the old wording.
     */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public ProposalTemplateService(ProposalProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Picks the template for a deal.
     *
     * <pre>
     *   explicit proposalType in the request  -> that one
     *   no product on the opportunity         -> PROFESSIONAL_SERVICES_PROPOSAL
     *   value >= proposal.implementation-value-threshold
     *                                         -> SOFTWARE_IMPLEMENTATION_PROPOSAL
     *   otherwise                             -> STANDARD_SALES_PROPOSAL
     * </pre>
     *
     * The product test comes first because it is a statement about what is being
     * sold, and the value test only about how much: a large services engagement
     * is still a services engagement, and printing a unit-price schedule for it
     * would leave the quantity column meaningless.
     */
    public ProposalType resolve(ProposalAssembly assembly, String requestedType) {
        if (requestedType != null && !requestedType.isBlank()) {
            return parse(requestedType);
        }

        if (!assembly.hasProductLines()) {
            return ProposalType.PROFESSIONAL_SERVICES_PROPOSAL;
        }

        BigDecimal threshold = properties.getImplementationValueThreshold();
        if (threshold != null && assembly.totalAmount().compareTo(threshold) >= 0) {
            return ProposalType.SOFTWARE_IMPLEMENTATION_PROPOSAL;
        }

        return ProposalType.STANDARD_SALES_PROPOSAL;
    }

    private ProposalType parse(String requestedType) {
        String normalised = requestedType.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return ProposalType.valueOf(normalised);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown proposal type: " + requestedType + ". Known types: "
                            + String.join(", ", Arrays.stream(ProposalType.values()).map(Enum::name).toList()));
        }
    }

    /** The location this type resolves to — stored on the proposal as
     *  {@code template_key} so a generated document stays explainable after the
     *  template set moves on. */
    public String templateKey(ProposalType type) {
        String override = properties.getTemplates().getOverrides().get(type.name());
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String location = properties.getTemplates().getLocation();
        String separator = location.endsWith("/") ? "" : "/";
        return location + separator + type.defaultTemplateFile();
    }

    /**
     * Reads the template. A missing or unreadable template is a server-side
     * misconfiguration, not a bad request, so it surfaces as 500 rather than
     * being blamed on the caller.
     */
    public byte[] load(ProposalType type) {
        String key = templateKey(type);
        return cache.computeIfAbsent(key, location -> {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Proposal template for " + type.name() + " is not available");
            }
            try (InputStream in = resource.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                log.info("Loaded proposal template {} ({} bytes) from {}", type.name(), bytes.length, location);
                return bytes;
            } catch (IOException e) {
                log.error("Could not read proposal template {} from {}", type.name(), location, e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Proposal template for " + type.name() + " could not be read");
            }
        });
    }

    /** Used by the startup check so a missing template is found on boot rather
     *  than by the first sales executive to click Generate. */
    public boolean isAvailable(ProposalType type) {
        return resourceLoader.getResource(templateKey(type)).exists();
    }
}
