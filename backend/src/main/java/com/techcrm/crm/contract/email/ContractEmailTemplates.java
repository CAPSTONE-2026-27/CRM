package com.techcrm.crm.contract.email;

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
 * The wording of the contract email, kept in three editable files:
 *
 * <pre>
 *   contract-email-subject.txt   the subject line
 *   contract-email.html          the formatted body
 *   contract-email.txt           the plain-text body, for mail clients without HTML
 * </pre>
 *
 * Placeholders are written {@code ${name}}. The files are read on every send and
 * never cached, so whoever owns the wording can change it while the backend is
 * running and the next email uses it.
 *
 * Values are HTML-escaped in the HTML body only: a customer called
 * "O'Brien &amp; Sons" must not break the markup, but must not arrive as
 * "O&amp;#39;Brien" in the subject line either.
 */
@Service
public class ContractEmailTemplates {

    private static final Logger log = LoggerFactory.getLogger(ContractEmailTemplates.class);

    static final String SUBJECT = "contract-email-subject.txt";
    static final String HTML = "contract-email.html";
    static final String TEXT = "contract-email.txt";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    private final MailjetProperties properties;
    private final ResourceLoader resourceLoader;

    public ContractEmailTemplates(MailjetProperties properties, ResourceLoader resourceLoader) {
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
                log.warn("Contract email template uses unknown placeholder ${{}}; known: {}", name, values.keySet());
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
            log.error("Contract email template not found at {}", path);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Contract email template " + fileName + " is not available");
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Could not read contract email template {}", path, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Contract email template " + fileName + " could not be read");
        }
    }
}
