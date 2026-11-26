package com.techcrm.crm.marketing.email;

import com.techcrm.crm.marketing.abtest.AbTest;
import com.techcrm.crm.marketing.ai.ContentOutputValidator;
import com.techcrm.crm.marketing.analytics.CampaignAnalyticsService;
import com.techcrm.crm.marketing.api.MarketingDtos.Funnel;
import com.techcrm.crm.marketing.campaign.CampaignChannels;
import com.techcrm.crm.marketing.campaign.MarketingCampaign;
import com.techcrm.crm.marketing.content.ContentChannel;
import com.techcrm.crm.marketing.execution.DeliveryProvider.Kind;
import com.techcrm.crm.marketing.execution.DeliveryProvider.OutgoingEmail;
import com.techcrm.crm.marketing.execution.DeliveryProvider.Result;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import jakarta.mail.internet.InternetAddress;
import java.io.*;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDeliveryTest {

    static EmailSettings settings(String host, int port, String publicBase, boolean tracking) {
        return new EmailSettings(host, port, null, null, "NONE", 5000, "news@acme.example", "@acme.example,team@other.example",
                publicBase, "test-link-secret", "hook-secret", "1 Example Road, Pune", tracking, 3);
    }

    @Nested
    class EmailOnly {

        @Test
        void everyCampaignAndEveryAbElementIsEmail() {
            MarketingCampaign c = new MarketingCampaign();
            c.setContentConfigJson("{\"channels\":[\"EMAIL\",\"POSTER\",\"SMS\"]}");
            assertThat(CampaignChannels.configured(c)).containsExactly(ContentChannel.EMAIL);
            assertThat(CampaignChannels.generated(c)).containsExactly(ContentChannel.EMAIL);
            for (AbTest.Element e : AbTest.Element.values()) {
                assertThat(CampaignChannels.testedChannel(e, CampaignChannels.configured(c))).isEqualTo(ContentChannel.EMAIL);
            }
        }

        @ParameterizedTest
        @EnumSource(ContentChannel.class)
        void onlyEmailIsALiveChannel(ContentChannel channel) {
            assertThat(channel.isLegacy()).isEqualTo(channel != ContentChannel.EMAIL);
        }
    }

    @Nested
    class Validation {

        private final String brief = "FlowSuite. Book a demo. https://flowsuite.example/demo";

        @Test
        void supportsTheDocumentedMergeFields() {
            Map<String, String> f = email("Hi {{first_name}}", "For {{company_name}} and {{company}}: {{product_name}}");
            assertThat(ContentOutputValidator.validate(ContentChannel.EMAIL, f, brief).errors()).isEmpty();
            f.put("body", "Your rep {{rep_name}}");
            assertThat(ContentOutputValidator.validate(ContentChannel.EMAIL, f, brief).errors()).anyMatch(e -> e.contains("placeholder"));
        }

        @Test
        void ctaLinkMustBeHttpAndComeFromTheBrief() {
            Map<String, String> f = email("Hi", "Body");
            f.put("ctaUrl", "javascript:alert(1)");
            assertThat(ContentOutputValidator.validate(ContentChannel.EMAIL, f, brief).errors()).anyMatch(e -> e.contains("script"));
            f.put("ctaUrl", "ftp://flowsuite.example/demo");
            assertThat(ContentOutputValidator.validate(ContentChannel.EMAIL, f, brief).errors()).anyMatch(e -> e.contains("http(s) link"));
            f.put("ctaUrl", "https://evil.example/x");
            assertThat(ContentOutputValidator.validate(ContentChannel.EMAIL, f, brief).errors()).anyMatch(e -> e.contains("does not appear"));
            f.put("ctaUrl", "https://flowsuite.example/demo");
            assertThat(ContentOutputValidator.validate(ContentChannel.EMAIL, f, brief).errors()).isEmpty();
        }

        private Map<String, String> email(String subject, String body) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("subject", subject);
            f.put("body", body);
            f.put("cta", "Book a demo");
            return f;
        }
    }

    @Nested
    class Rendering {

        private final EmailRenderer.Context ctx = new EmailRenderer.Context("Asha at Acme", "FlowSuite", "Q4", "https://flowsuite.example/demo",
                "1 Example Road", "https://crm.example/api/marketing/public/c/1.sig", "https://crm.example/api/marketing/public/o/1.sig.gif",
                "https://crm.example/api/marketing/public/u/1.sig", true);

        @Test
        void escapesContentAndProducesHtmlAndText() {
            Map<String, String> f = Map.of("subject", "Hello <b>{{first_name}}</b>", "body", "Line <script>x</script>\n\n- One\n- Two",
                    "cta", "Book a demo");
            EmailRenderer.Rendered r = EmailRenderer.render(f, new EmailRenderer.Recipient("p@x.example", "Priya", "Acme"), ctx);
            assertThat(r.subject()).isEqualTo("Hello <b>Priya</b>");
            assertThat(r.html()).doesNotContain("<script>").contains("&lt;script&gt;").contains("<li>One</li>").contains("<li>Two</li>");
            assertThat(r.html()).contains("href=\"https://crm.example/api/marketing/public/c/1.sig\"")
                    .contains("public/u/1.sig").contains("public/o/1.sig.gif").contains("1 Example Road");
            assertThat(r.text()).contains("Hello Priya,").contains("Book a demo: https://crm.example/api/marketing/public/c/1.sig")
                    .contains("Unsubscribe: https://crm.example/api/marketing/public/u/1.sig").doesNotContain("<li>");
        }

        @Test
        void missingValuesFallBackAndAreReported() {
            Map<String, String> f = Map.of("subject", "For {{company_name}}", "body", "Hi {{first_name}} about {{product_name}}", "cta", "Go");
            EmailRenderer.Rendered r = EmailRenderer.render(f, new EmailRenderer.Recipient("p@x.example", null, " "), ctx);
            assertThat(r.subject()).isEqualTo("For your company");
            assertThat(r.text()).contains("Hi there about FlowSuite");
            assertThat(r.notes()).anyMatch(n -> n.contains("{{first_name}}")).anyMatch(n -> n.contains("{{company_name}}"));
        }

        @Test
        void unsafeCtaLinkIsDropped() {
            Map<String, String> f = Map.of("subject", "S", "body", "B", "cta", "Go", "ctaUrl", "javascript:alert(1)");
            EmailRenderer.Context noDefault = new EmailRenderer.Context("A", null, "Q4", null, null, null, null, "https://u", true);
            EmailRenderer.Rendered r = EmailRenderer.render(f, new EmailRenderer.Recipient("p@x.example", "P", "C"), noDefault);
            assertThat(r.html()).doesNotContain("javascript:");
            assertThat(r.ctaUrl()).isNull();
        }
    }

    @Nested
    class SettingsAndLinks {

        @Test
        void configurationRequiredUntilHostSenderAndPublicUrlExist() {
            EmailSettings none = new EmailSettings("", 587, null, null, "STARTTLS", 1000, "", "", "", "s", null, null, true, 3);
            assertThat(none.configured()).isFalse();
            assertThat(none.missing()).hasSize(3);
            assertThat(none.status().provider()).isEqualTo("Not configured");
            assertThat(settings("smtp.example", 587, "https://crm.example", true).configured()).isTrue();
        }

        @Test
        void verifiedSendersMatchExactlyOrByDomain() {
            EmailSettings s = settings("smtp.example", 587, "https://crm.example", true);
            assertThat(s.isVerifiedSender("NEWS@acme.example")).isTrue();
            assertThat(s.isVerifiedSender("sales@acme.example")).isTrue();
            assertThat(s.isVerifiedSender("team@other.example")).isTrue();
            assertThat(s.isVerifiedSender("x@other.example")).isFalse();
            assertThat(s.isVerifiedSender("not-an-address")).isFalse();
        }

        @Test
        void linkTokensAreBoundToPurposeAndMessage() {
            EmailLinks links = new EmailLinks(settings("smtp.example", 587, "https://crm.example/", true));
            String token = links.token(EmailLinks.Purpose.UNSUBSCRIBE, 42);
            assertThat(links.verify(EmailLinks.Purpose.UNSUBSCRIBE, token)).isEqualTo(42L);
            assertThat(links.verify(EmailLinks.Purpose.CLICK, token)).isNull();
            assertThat(links.verify(EmailLinks.Purpose.UNSUBSCRIBE, "43" + token.substring(2))).isNull();
            assertThat(links.verify(EmailLinks.Purpose.UNSUBSCRIBE, "garbage")).isNull();
            assertThat(links.unsubscribeUrl(42)).startsWith("https://crm.example/api/marketing/public/u/42.");
        }

        @Test
        void trackingOffMeansNoTrackingLinksButStillUnsubscribe() {
            EmailLinks links = new EmailLinks(settings("smtp.example", 587, "https://crm.example", false));
            assertThat(links.clickUrl(1)).isNull();
            assertThat(links.openPixelUrl(1)).isNull();
            assertThat(links.unsubscribeUrl(1)).isNotNull();
        }
    }

    @Nested
    class Analytics {

        @Test
        void acceptanceIsNotDeliveryAndRatesExcludeBounces() {
            Funnel f = CampaignAnalyticsService.funnel(100, 0, 20, 40, 8, 0, 4, 2, 3, 5);
            assertThat(f.deliveryRate()).as("no delivery events: not reported rather than 0%").isNull();
            assertThat(f.bounceRate()).isEqualTo(20.0);
            assertThat(f.openRate()).isEqualTo(50.0);
            assertThat(f.clickRate()).isEqualTo(10.0);
            assertThat(f.conversionRate()).isEqualTo(5.0);
            assertThat(f.unsubscribeRate()).isEqualTo(2.5);
            assertThat(CampaignAnalyticsService.funnel(100, 90, 0, 0, 0, 0, 0, 0, 0, 0).deliveryRate()).isEqualTo(90.0);
        }
    }

    @Nested
    class SmtpErrors {

        @Test
        void classifiesFailuresForRetry() throws Exception {
            InternetAddress rcpt = new InternetAddress("x@y.example");
            assertThat(SmtpDeliveryProvider.classify(new AuthenticationFailedException("no")).kind()).isEqualTo(Kind.PROVIDER_ERROR);
            assertThat(SmtpDeliveryProvider.classify(wrap(new SMTPAddressFailedException(rcpt, "RCPT", 550, "no such user"))).kind())
                    .isEqualTo(Kind.PERMANENT);
            assertThat(SmtpDeliveryProvider.classify(wrap(new SMTPAddressFailedException(rcpt, "RCPT", 451, "try later"))).kind())
                    .isEqualTo(Kind.TRANSIENT);
            assertThat(SmtpDeliveryProvider.classify(new MessagingException("connect", new ConnectException("refused"))).kind())
                    .isEqualTo(Kind.TRANSIENT);
        }

        private MessagingException wrap(Exception e) {
            return new SendFailedException("failed", e);
        }
    }

    /** Sends through the real SMTP code path to an in-process SMTP server. */
    @Nested
    class SmtpRoundTrip {

        @Test
        void sendsMultipartEmailWithUnsubscribeHeadersAndRejectsBadRecipients() throws Exception {
            try (FakeSmtpServer server = new FakeSmtpServer()) {
                SmtpDeliveryProvider smtp = new SmtpDeliveryProvider(settings("127.0.0.1", server.port(), "https://crm.example", true));
                assertThat(smtp.configured()).isTrue();

                Result ok = smtp.send(new OutgoingEmail(7, "Asha at Acme", "news@acme.example", "reply@acme.example",
                        "priya@client.example", "Hello Priya", "<p>Hi</p>", "Hi", "https://crm.example/api/marketing/public/u/7.x"));
                assertThat(ok.kind()).isEqualTo(Kind.ACCEPTED);
                assertThat(ok.providerMessageId()).startsWith("<mkt-7-").endsWith("@acme.example>");

                String data = server.lastData();
                assertThat(data).contains("Subject: Hello Priya").contains("To: priya@client.example")
                        .contains("List-Unsubscribe: <https://crm.example/api/marketing/public/u/7.x>")
                        .contains("List-Unsubscribe-Post: List-Unsubscribe=One-Click")
                        .contains("multipart/alternative").contains("text/plain").contains("text/html")
                        .contains("Message-ID: " + ok.providerMessageId());

                Result bad = smtp.send(new OutgoingEmail(8, "A", "news@acme.example", null, "bounce@client.example",
                        "S", "<p>x</p>", "x", "https://u"));
                assertThat(bad.kind()).isEqualTo(Kind.PERMANENT);
            }
        }
    }

    /** Minimal SMTP server: accepts everything except recipients starting with "bounce@". */
    static final class FakeSmtpServer implements AutoCloseable {

        private final ServerSocket socket = new ServerSocket(0);
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final CompletableFuture<Void> loop;

        FakeSmtpServer() throws IOException {
            loop = CompletableFuture.runAsync(() -> {
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        session(s);
                    } catch (IOException ignored) {
                        // closed
                    }
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        String lastData() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }

        private void session(Socket s) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            reply(out, "220 fake ESMTP");
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.toUpperCase(Locale.ROOT);
                if (cmd.startsWith("EHLO") || cmd.startsWith("HELO")) reply(out, "250 fake");
                else if (cmd.startsWith("MAIL FROM")) reply(out, "250 OK");
                else if (cmd.startsWith("RCPT TO")) reply(out, cmd.contains("BOUNCE@") ? "550 5.1.1 No such user" : "250 OK");
                else if (cmd.startsWith("DATA")) {
                    reply(out, "354 End data with <CR><LF>.<CR><LF>");
                    StringBuilder data = new StringBuilder();
                    while ((line = in.readLine()) != null && !line.equals(".")) data.append(line).append('\n');
                    messages.add(data.toString());
                    reply(out, "250 OK queued");
                } else if (cmd.startsWith("RSET") || cmd.startsWith("NOOP")) reply(out, "250 OK");
                else if (cmd.startsWith("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else reply(out, "502 unsupported");
            }
        }

        private static void reply(Writer out, String text) throws IOException {
            out.write(text + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws Exception {
            socket.close();
            loop.get(5, TimeUnit.SECONDS);
        }
    }
}
