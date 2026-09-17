package com.techcrm.crm.deal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the request DealScoringClient.score(Deal) sends to XgBoost/serve_api.py.
 *
 * The scorer runs in strict mode, so an empty categorical is not a harmless
 * blank: an ordinal column rejects it with a 400 and the deal is silently left
 * unscored, and a one-hot column accepts it and zeroes every feature in that
 * group. A partially filled edit form must therefore send neutral defaults.
 */
class DealScoringClientTest {

    private HttpServer server;
    private DealScoringClient client;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/score", this::handle);
        server.start();
        client = new DealScoringClient("http://127.0.0.1:" + server.getAddress().getPort(), 5000);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            lastRequestBody.set(new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] payload = """
                {"deal_score": 55.0, "win_probability": 0.55, "band": "MEDIUM",
                 "action": "Nurture", "clipped": false, "model_version": "1.0.0"}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    /** Only the four fields hasScoringInputs() requires; everything else left null. */
    private static Deal partiallyFilledDeal() {
        Deal deal = new Deal();
        deal.setName("Partial deal");
        deal.setCustomerSentiment("Positive");
        deal.setBuyingIntent("High");
        deal.setBudgetStatus("Fully Approved");
        deal.setMeetingOutcome("Proposal Sent");
        return deal;
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullCategoricalsAreSentAsNeutralDefaultsNotEmptyStrings() throws IOException {
        assertThat(client.score(partiallyFilledDeal())).isNotNull();

        Map<String, Object> sent = new ObjectMapper().readValue(lastRequestBody.get(), Map.class);
        assertThat(sent).hasSize(17);
        assertThat(sent.values()).noneMatch(value -> value instanceof String s && s.isBlank());
        assertThat(sent)
                .containsEntry("decision_maker_involvement", "Indirect")
                .containsEntry("customer_urgency", "Medium")
                .containsEntry("product_interest_level", "Medium")
                .containsEntry("customer_requirements", "Standard Package")
                .containsEntry("risk_factors", "No Risk Identified")
                .containsEntry("competitor_mention", "No")
                .containsEntry("implementation_readiness", "Partially Ready")
                .containsEntry("upsell_opportunity", "No")
                .containsEntry("main_objections", "No Objections");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankCategoricalsAreTreatedLikeMissingOnes() throws IOException {
        Deal deal = partiallyFilledDeal();
        deal.setCustomerUrgency("  ");

        client.score(deal);

        Map<String, Object> sent = new ObjectMapper().readValue(lastRequestBody.get(), Map.class);
        assertThat(sent).containsEntry("customer_urgency", "Medium");
    }

    @Test
    @SuppressWarnings("unchecked")
    void suppliedValuesArePassedThroughUntouched() throws IOException {
        Deal deal = partiallyFilledDeal();
        deal.setCustomerUrgency("Critical");
        deal.setRiskFactors("Competitor Pressure");

        client.score(deal);

        Map<String, Object> sent = new ObjectMapper().readValue(lastRequestBody.get(), Map.class);
        assertThat(sent)
                .containsEntry("customer_sentiment", "Positive")
                .containsEntry("customer_urgency", "Critical")
                .containsEntry("risk_factors", "Competitor Pressure");
    }
}
