package com.techcrm.crm.email;

import com.techcrm.crm.lead.LeadRequest;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Light heuristic extraction from an email into a lead — no ML/NLP, by
 * design (see project notes on email ingestion scope). Company, industry,
 * product, and deal value are left null; only what a plain email actually
 * carries (sender name/address, subject, body) is populated. Two sources
 * feed the same {@link #build}: a real IMAP-fetched Message ({@link #parse})
 * and manually pasted text ({@link #parsePasted}) for testing/interim use
 * before a mailbox is configured.
 */
@Component
public class EmailLeadParser {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("([\\w.+-]+@[\\w-]+\\.[\\w.-]+)");

    public LeadRequest parse(Message message) throws MessagingException, IOException {
        String email = extractEmail(message);
        String fullName = extractFullName(message, email);
        String body = extractText(message);
        return build(fullName, email, message.getSubject(), body);
    }

    /** For the "paste an email in manually" flow — from/subject are
     *  whatever free text the user typed, not validated header syntax. */
    public LeadRequest parsePasted(String from, String subject, String body) {
        String email = extractEmailFromFreeText(from);
        String fullName = deriveFullName(from, email);
        return build(fullName, email, subject, body);
    }

    private LeadRequest build(String fullName, String email, String subject, String body) {
        String notes = buildNotes(subject, body);
        return new LeadRequest(
                fullName,
                null,
                null,
                null,
                email,
                null,
                null,
                null,
                "Email",
                "EMAIL_PARSING",
                notes,
                null,
                null
        );
    }

    private String extractEmail(Message message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return null;
        }
        if (from[0] instanceof InternetAddress ia) {
            return ia.getAddress();
        }
        return from[0].toString();
    }

    private String extractFullName(Message message, String email) throws MessagingException {
        Address[] from = message.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            String personal = ia.getPersonal();
            if (personal != null && !personal.isBlank()) {
                return personal.trim();
            }
        }
        return deriveFullNameFromEmail(email);
    }

    private String extractEmailFromFreeText(String from) {
        if (from == null || from.isBlank()) {
            return null;
        }
        Matcher m = EMAIL_PATTERN.matcher(from);
        return m.find() ? m.group(1) : null;
    }

    /** "From" pasted as free text: could be "Name <email>", just an email,
     *  or just a name — pull out whichever parts are actually present. */
    private String deriveFullName(String from, String email) {
        if (from != null && !from.isBlank()) {
            String withoutEmail = email != null ? from.replace(email, "") : from;
            String cleaned = withoutEmail.replaceAll("[<>()]", "").trim();
            if (!cleaned.isBlank()) {
                return cleaned;
            }
        }
        return deriveFullNameFromEmail(email);
    }

    private String deriveFullNameFromEmail(String email) {
        if (email != null && email.contains("@")) {
            String localPart = email.substring(0, email.indexOf('@'));
            StringBuilder name = new StringBuilder();
            for (String piece : localPart.split("[._+-]+")) {
                if (piece.isBlank()) {
                    continue;
                }
                if (!name.isEmpty()) {
                    name.append(' ');
                }
                name.append(Character.toUpperCase(piece.charAt(0))).append(piece.substring(1));
            }
            if (!name.isEmpty()) {
                return name.toString();
            }
        }
        return "Unknown Sender";
    }

    private String buildNotes(String subject, String body) {
        StringBuilder sb = new StringBuilder();
        if (subject != null && !subject.isBlank()) {
            sb.append("Subject: ").append(subject.trim());
        }
        if (body != null && !body.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(body.trim());
        }
        String notes = sb.toString();
        return notes.length() > 1000 ? notes.substring(0, 1000) : notes;
    }

    private String extractText(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content != null ? content.toString() : null;
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return content != null ? stripHtml(content.toString()) : null;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                Part bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("text/plain")) {
                    return extractText(bodyPart);
                }
            }
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = extractText(multipart.getBodyPart(i));
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
