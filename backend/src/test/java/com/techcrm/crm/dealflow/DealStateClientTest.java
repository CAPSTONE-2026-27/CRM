package com.techcrm.crm.dealflow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract with DealIntelligence_CRM/scripts/serve.py.
 *
 * Nothing here loads a model. The thing worth testing is the agreement between
 * two codebases in different languages that cannot import each other: the
 * request shape Java sends and the response shape it expects back. A drift
 * there is silent — the service keeps answering, Java keeps parsing, and the
 * fields it fails to read simply fall back to defaults at scoring time, where
 * one-hot columns accept the wrong value without raising.
 *
 * The fixtures below are the literal shapes in serve.py's DealStateRequest and
 * DealStateResponse models. If those change, these fail.
 */
class DealStateClientTest {

    private HttpServer server;
    private String baseUrl;

    /** Whatever the last request body was, so the request shape can be asserted. */
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    /** What the stub answers with, per test. */
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    private static final String FULL_STATE = """
            {"total_meetings": 3, "lead_score": 46, "customer_sentiment": "Positive",
             "buying_intent": "High", "relationship_strength": 7.0,
             "budget_status": "Fully Approved", "decision_maker_involvement": "Yes",
             "customer_urgency": "High", "main_objections": "No Objections",
             "product_interest_level": "High", "meeting_outcome": "Proposal Sent",
             "customer_requirements": "Customized Integration",
             "risk_factors": "Competitor Pressure", "competitor_mention": "Yes",
             "engagement_score": 83.4, "implementation_readiness": "Ready",
             "upsell_opportunity": "Yes"}
            """;

    @BeforeEach
    void startStub() throws IOException {
        // Port 0: the OS picks a free one, so the test never collides with a
        // real service or with a parallel run of itself.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/deal-state", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            lastRequestBody.set(new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] payload = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private DealStateClient client() {
        return new DealStateClient(baseUrl, 5000);
    }

    private MeetingOutput meeting() {
        MeetingOutput meeting = new MeetingOutput();
        meeting.setId(1L);
        meeting.setVersion(3);
        meeting.setMeetingSummary("The CFO confirmed the full budget is approved.");
        return meeting;
    }

    private Map<String, Object> previousState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("total_meetings", 2);
        state.put("budget_status", "Under Review");
        state.put("customer_requirements", "Customized Integration");
        return state;
    }

    @Nested
    @DisplayName("the request it sends")
    class Request {

        @Test
        @DisplayName("carries the previous state when there is one")
        void sendsPreviousState() {
            response.set("{\"state\": " + FULL_STATE + ", \"changed_fields\": [], "
                    + "\"repairs\": [], \"adapter\": true, \"model_version\": \"1.0.0\", \"latency_ms\": 10}");

            client().update(previousState(), meeting());

            assertThat(lastRequestBody.get()).contains("\"previous_state\"");
            assertThat(lastRequestBody.get()).contains("\"meeting_notes\"");
            assertThat(lastRequestBody.get()).contains("Under Review");
        }

        @Test
        @DisplayName("omits previous_state on the first meeting rather than sending null")
        void omitsPreviousStateWhenAbsent() {
            // serve.py reads an absent key as "first meeting on this
            // opportunity" and starts from its own defaults. Sending the key
            // with an empty object instead would have it coerce {} into a full
            // neutral state and report seventeen repairs.
            response.set("{\"state\": " + FULL_STATE + ", \"changed_fields\": [], "
                    + "\"repairs\": [], \"adapter\": true, \"model_version\": \"1.0.0\", \"latency_ms\": 10}");

            client().update(null, meeting());

            assertThat(lastRequestBody.get()).doesNotContain("previous_state");
            assertThat(lastRequestBody.get()).contains("meeting_notes");
        }

        @Test
        @DisplayName("does not call the service at all for a write-up with no content")
        void skipsEmptyMeeting() {
            MeetingOutput empty = new MeetingOutput();
            empty.setId(2L);
            empty.setVersion(1);
            lastRequestBody.set(null);

            assertThat(client().update(null, empty)).isNull();
            assertThat(lastRequestBody.get()).isNull();
        }
    }

    @Nested
    @DisplayName("the response it reads")
    class Response {

        @Test
        @DisplayName("reads all seventeen fields, keeping numbers as numbers")
        void readsState() {
            response.set("{\"state\": " + FULL_STATE + ", \"changed_fields\": [\"budget_status\"], "
                    + "\"repairs\": [], \"adapter\": true, \"model_version\": \"1.0.0\", \"latency_ms\": 4210}");

            DealStateClient.DealStateResult result = client().update(previousState(), meeting());

            assertThat(result).isNotNull();
            assertThat(result.state()).hasSize(17);
            assertThat(result.state().get("budget_status")).isEqualTo("Fully Approved");
            // Numbers must not arrive as strings: FeatureEngineeringService
            // re-derives these, but relationship_strength is passed through and
            // serve_api declares it a float.
            assertThat(result.state().get("relationship_strength")).isInstanceOf(Number.class);
            assertThat(result.modelVersion()).isEqualTo("1.0.0");
        }

        @Test
        @DisplayName("reads changed_fields, which is what replaces a confidence score")
        void readsChangedFields() {
            response.set("{\"state\": " + FULL_STATE + ", "
                    + "\"changed_fields\": [\"budget_status\", \"buying_intent\"], "
                    + "\"repairs\": [], \"adapter\": true, \"model_version\": \"1.0.0\", \"latency_ms\": 10}");

            DealStateClient.DealStateResult result = client().update(previousState(), meeting());

            assertThat(result.changedFields()).containsExactly("budget_status", "buying_intent");
        }

        @Test
        @DisplayName("reads repairs, the only signal that a value was silently corrected")
        void readsRepairs() {
            response.set("{\"state\": " + FULL_STATE + ", \"changed_fields\": [], "
                    + "\"repairs\": [\"risk_factors='Budget Risk'->default 'No Risk Identified'\"], "
                    + "\"adapter\": true, \"model_version\": \"1.0.0\", \"latency_ms\": 10}");

            DealStateClient.DealStateResult result = client().update(previousState(), meeting());

            assertThat(result.repairs()).hasSize(1);
            // DealFlowService splits on the first '=' to name the repaired
            // field, so the "field=..." prefix is part of the contract.
            assertThat(result.repairs().get(0)).startsWith("risk_factors=");
        }

        @Test
        @DisplayName("reports adapter:false, which means the reply is not production output")
        void readsAdapterFlag() {
            response.set("{\"state\": " + FULL_STATE + ", \"changed_fields\": [], "
                    + "\"repairs\": [], \"adapter\": false, \"model_version\": \"1.0.0\", \"latency_ms\": 10}");

            DealStateClient.DealStateResult result = client().update(previousState(), meeting());

            assertThat(result.adapterLoaded()).isFalse();
        }
    }

    @Nested
    @DisplayName("when the service cannot answer")
    class Degradation {

        @Test
        @DisplayName("returns null on a 503 so the caller can fall back")
        void nullOnServiceUnavailable() {
            // serve.py answers 503 while the model is still loading, which is a
            // normal state for the first minute after start-up.
            status.set(503);
            response.set("{\"error\": \"Model is still loading\"}");

            assertThat(client().update(previousState(), meeting())).isNull();
        }

        @Test
        @DisplayName("returns null when the reply carries no state")
        void nullOnMissingState() {
            status.set(200);
            response.set("{\"changed_fields\": [], \"repairs\": [], \"adapter\": true}");

            assertThat(client().update(previousState(), meeting())).isNull();
        }

        @Test
        @DisplayName("is off entirely when no base URL is configured")
        void offWhenUnconfigured() {
            // Blank means off: an installation that has not started the service
            // keeps the previous single-meeting behaviour instead of failing
            // every submission.
            DealStateClient unconfigured = new DealStateClient("", 5000);

            assertThat(unconfigured.isConfigured()).isFalse();
            assertThat(unconfigured.update(previousState(), meeting())).isNull();
        }
    }
}
