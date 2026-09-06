package com.techcrm.crm.contract.template;

import com.techcrm.crm.contract.ContractAssembly;
import com.techcrm.crm.contract.ContractProperties;
import com.techcrm.crm.contract.ContractType;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses a contract template and loads its bytes.
 *
 * The choice lives here rather than in the controller or the generation service
 * so that adding a fourth template is one branch in one method. Templates
 * themselves are ordinary .docx files — editable in Word by whoever owns the
 * contract wording, without touching Java.
 */
@Service
public class ContractTemplateService {

    private static final Logger log = LoggerFactory.getLogger(ContractTemplateService.class);

    private final ContractProperties properties;
    private final ResourceLoader resourceLoader;

    /**
     * Templates change about once a quarter and are a few tens of kilobytes, so
     * they are read once and kept. Keyed by resolved location rather than by
     * type, so pointing a type at a different file through configuration
     * naturally misses the cache instead of serving the old wording.
     */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public ContractTemplateService(ContractProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Picks the template for a deal.
     *
     * <pre>
     *   explicit contractType in the request  -> that one
     *   no product on the opportunity         -> PROFESSIONAL_SERVICES_AGREEMENT
     *   value >= contract.enterprise-value-threshold
     *                                         -> ENTERPRISE_SUBSCRIPTION_AGREEMENT
     *   otherwise                             -> STANDARD_SALES_AGREEMENT
     * </pre>
     *
     * The product test comes first because it is a statement about what is being
     * sold, and the value test only about how much: a large services engagement
     * is still a services engagement, and printing a unit-price schedule for it
     * would leave the quantity column meaningless.
     */
    public ContractType resolve(ContractAssembly assembly, String requestedType) {
        if (requestedType != null && !requestedType.isBlank()) {
            return parse(requestedType);
        }

        if (!assembly.hasProductLines()) {
            return ContractType.PROFESSIONAL_SERVICES_AGREEMENT;
        }

        BigDecimal threshold = properties.getEnterpriseValueThreshold();
        if (threshold != null && assembly.totalAmount().compareTo(threshold) >= 0) {
            return ContractType.ENTERPRISE_SUBSCRIPTION_AGREEMENT;
        }

        return ContractType.STANDARD_SALES_AGREEMENT;
    }

    private ContractType parse(String requestedType) {
        String normalised = requestedType.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return ContractType.valueOf(normalised);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown contract type: " + requestedType + ". Known types: "
                            + String.join(", ", java.util.Arrays.stream(ContractType.values())
                            .map(Enum::name).toList()));
        }
    }

    /** The location this type resolves to — stored on the contract as
     *  {@code template_key} so a generated document stays explainable after the
     *  template set moves on. */
    public String templateKey(ContractType type) {
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
    public byte[] load(ContractType type) {
        String key = templateKey(type);
        return cache.computeIfAbsent(key, location -> {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Contract template for " + type.name() + " is not available");
            }
            try (InputStream in = resource.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                log.info("Loaded contract template {} ({} bytes) from {}", type.name(), bytes.length, location);
                return bytes;
            } catch (IOException e) {
                log.error("Could not read contract template {} from {}", type.name(), location, e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Contract template for " + type.name() + " could not be read");
            }
        });
    }

    /** Used by the startup check so a missing template is found on boot rather
     *  than by the first sales executive to click Generate. */
    public boolean isAvailable(ContractType type) {
        return resourceLoader.getResource(templateKey(type)).exists();
    }
}
