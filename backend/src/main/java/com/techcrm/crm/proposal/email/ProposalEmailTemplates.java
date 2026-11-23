package com.techcrm.crm.proposal.email;

import com.techcrm.crm.contract.email.MailjetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wording of the proposal email, kept in three editable files alongside the
 * contract ones:
 *
 * <pre>
 *   proposal-email-subject.txt   the subject line
 *   proposal-email.html          the formatted body
 *   proposal-email.txt           the plain-text body
 * </pre>
 *
 * A separate set of files rather than the contract's, because the two documents
 * ask the customer for different things: a contract asks them to accept terms,
 * a proposal asks them to consider an offer that expires. Sharing one template
 * would have meant wording vague enough to cover both.
 *
 * Read on every send and never cached, so whoever owns the wording can change it
 * while the backend runs. Placeholders are {@code ${name}}, HTML-escaped in the
 * HTML body only — for the reasons given on
 * {@link com.techcrm.crm.contract.email.ContractEmailTemplates}.
 */
@Service
public class ProposalEmailTemplates {

    private static final Logger log = LoggerFactory.getLogger(ProposalEmailTemplates.class);

    static final String SUBJECT = "proposal-email-subject.txt";
    static final String HTML = "proposal-email.html";
    static final String TEXT = "proposal-email.txt";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private final MailjetProperties properties;
    private final ResourceLoader resourceLoader;

    public ProposalEmailTemplates(MailjetProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    public record Rendered(String subject, String html, String text) {
    }

    public Rendered render(Map<String, String> values) {
        return new Rendered(
                // A subject is one line; a stray newline from an editor would
                // otherwise be rejected by the mail provider.
                fill(read(SUBJECT), values, false).replaceAll("\\s*\\R\\s*", " ").trim(),
                fill(read(HTML), values, true),
                fill(read(TEXT), values, false));
    }

    private String fill(String template, Map<String, String> values, boolean escapeHtml) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement;
            if (values.containsKey(name)) {
                String value = values.get(name) == null ? "" : values.get(name);
                replacement = escapeHtml ? HtmlUtils.htmlEscape(value) : value;
            } else {
                // Left visible rather than blanked, so a typo in the template
                // shows up in a test email instead of silently dropping text.
                log.warn("Proposal email template uses unknown placeholder ${{}}; known: {}", name, values.keySet());
                replacement = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String read(String fileName) {
        String location = properties.getTemplates().getLocation();
        String path = location + (location.endsWith("/") ? "" : "/") + fileName;

        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            log.error("Proposal email template not found at {}", path);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Proposal email template " + fileName + " is not available");
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Could not read proposal email template {}", path, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Proposal email template " + fileName + " could not be read");
        }
    }
}
