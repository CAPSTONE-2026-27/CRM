package com.techcrm.crm.sap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The URL the client builds, which is the one thing about it worth asserting
 * without a live platform.
 *
 * Whether the environment has to be named in the query string is a property of
 * the tenant: some landscapes answer "definition not found" without it, others
 * reject it. Both have to work, so both are pinned here.
 */
class SapProcessTriggerClientTest {

    private static final String BASE =
            "https://spa-api-gateway.example.com/workflow/rest/v1";

    @Nested
    @DisplayName("building a workflow API url")
    class ApiUrl {

        @Test
        void leavesTheUrlAloneWhenNoEnvironmentIsConfigured() {
            assertThat(url(properties(BASE, ""), "/workflow-instances"))
                    .isEqualTo(BASE + "/workflow-instances");
        }

        @Test
        void namesTheEnvironmentWhenOneIsConfigured() {
            assertThat(url(properties(BASE, "development101"), "/workflow-instances"))
                    .isEqualTo(BASE + "/workflow-instances?environmentId=development101");
        }

        /** A trailing slash in configuration is a typo, not a different URL. */
        @Test
        void toleratesATrailingSlashOnTheBase() {
            assertThat(url(properties(BASE + "/", ""), "/workflow-instances"))
                    .isEqualTo(BASE + "/workflow-instances");
        }

        @Test
        void encodesAnEnvironmentThatNeedsIt() {
            assertThat(url(properties(BASE, "dev 101"), "/workflow-instances"))
                    .endsWith("?environmentId=dev+101");
        }

        @Test
        void appliesToReadsAsWellAsStarts() {
            assertThat(url(properties(BASE, "development101"), "/workflow-instances/abc/context"))
                    .isEqualTo(BASE + "/workflow-instances/abc/context?environmentId=development101");
        }
    }

    private static SapProcessProperties properties(String apiBaseUrl, String environmentId) {
        SapProcessProperties properties = new SapProcessProperties();
        properties.setApiBaseUrl(apiBaseUrl);
        properties.setEnvironmentId(environmentId);
        return properties;
    }

    /** api() is private because nothing outside the client should build these;
     *  it is still the piece most worth pinning. */
    private static String url(SapProcessProperties properties, String path) {
        try {
            SapProcessTriggerClient client = new SapProcessTriggerClient(properties);
            Method api = SapProcessTriggerClient.class.getDeclaredMethod("api", String.class);
            api.setAccessible(true);
            return (String) api.invoke(client, path);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not reach api()", e);
        }
    }
}
