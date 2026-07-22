package com.techcrm.crm.email;

import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRequest;
import com.techcrm.crm.lead.LeadService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Polls a single configured mailbox for unread mail and creates a lead per
 * message (see EmailLeadParser). A shared mailbox has no per-message tenant
 * signal, so v1 assumes one mailbox maps to exactly one organization (set
 * via app.email-ingestion.organization-id) — multi-mailbox routing is a
 * later extension if it's ever needed.
 *
 * Stays inert (no exception, no crash) whenever email-ingestion isn't
 * enabled or is missing credentials, so a misconfigured/absent mailbox
 * never blocks the rest of the app from starting or running.
 */
@Service
public class ImapPollingService {

    private static final Logger log = LoggerFactory.getLogger(ImapPollingService.class);

    private final LeadService leadService;
    private final EmailLeadParser parser;

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final Long organizationId;

    public ImapPollingService(
            LeadService leadService,
            EmailLeadParser parser,
            @Value("${app.email-ingestion.enabled:false}") boolean enabled,
            @Value("${app.email-ingestion.host:imap.gmail.com}") String host,
            @Value("${app.email-ingestion.port:993}") int port,
            @Value("${app.email-ingestion.username:}") String username,
            @Value("${app.email-ingestion.password:}") String password,
            @Value("${app.email-ingestion.organization-id:}") String organizationIdRaw
    ) {
        this.leadService = leadService;
        this.parser = parser;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.organizationId = (organizationIdRaw == null || organizationIdRaw.isBlank()) ? null : Long.valueOf(organizationIdRaw);
    }

    @Scheduled(fixedDelayString = "${app.email-ingestion.poll-interval-ms:120000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        if (username == null || username.isBlank() || password == null || password.isBlank() || organizationId == null) {
            log.warn("Email ingestion is enabled but username/password/organization-id aren't fully set in application-local.yml — skipping poll.");
            return;
        }

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");

        Session session = Session.getInstance(props);

        Store store = null;
        Folder inbox = null;
        try {
            store = session.getStore("imaps");
            store.connect(host, port, username, password);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            Message[] unseen = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            if (unseen.length == 0) {
                return;
            }

            log.info("Email ingestion: found {} unseen message(s)", unseen.length);

            for (Message message : unseen) {
                try {
                    LeadRequest request = parser.parse(message);
                    Lead lead = leadService.createUnscoredForOrganization(organizationId, request);
                    leadService.scoreAsync(lead.getId(), organizationId);
                    message.setFlag(Flags.Flag.SEEN, true);
                } catch (Exception e) {
                    // Leave this one message unread so the next poll retries
                    // it, rather than losing it or aborting the whole batch.
                    log.warn("Could not ingest one email into a lead (left unread for retry): {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Email ingestion poll failed (mailbox unreachable or credentials invalid): {}", e.getMessage());
        } finally {
            closeQuietly(inbox, store);
        }
    }

    private void closeQuietly(Folder inbox, Store store) {
        try {
            if (inbox != null && inbox.isOpen()) {
                inbox.close(false);
            }
        } catch (MessagingException ignored) {
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (MessagingException ignored) {
        }
    }
}
