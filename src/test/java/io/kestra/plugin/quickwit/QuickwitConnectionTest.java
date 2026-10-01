package io.kestra.plugin.quickwit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickwit.search.Search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of the connection properties shared by every task: custom headers, basic auth and the error
 * raised when a protected cluster answers 401.
 */
class QuickwitConnectionTest extends AbstractQuickwitTest {
    private static final String SEARCH_OK = """
        {"num_hits": 0, "hits": []}
        """;

    @Test
    void sendsCustomHeaders(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson(SEARCH_OK))
        );

        Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .headers(Property.ofValue(Map.of("X-Api-Key", "secret-key")))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build()
            .run(runContextFactory.of());

        verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/api/v1/app-logs/search"))
            .withHeader("X-Api-Key", equalTo("secret-key"))
        );
    }

    @Test
    void rendersTemplatedHeaders(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson(SEARCH_OK))
        );

        Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .headers(Property.ofExpression("{\"Authorization\": \"Bearer {{ token }}\"}"))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build()
            .run(runContextFactory.of(Map.of("token", "abc123")));

        verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/api/v1/app-logs/search"))
            .withHeader("Authorization", equalTo("Bearer abc123"))
        );
    }

    @Test
    void sendsBasicAuthCredentials(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson(SEARCH_OK))
        );

        Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .basicAuth(AbstractQuickwitTask.BasicAuth.builder()
                .username(Property.ofValue("kestra"))
                .password(Property.ofValue("changeme"))
                .build()
            )
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build()
            .run(runContextFactory.of());

        var expected = "Basic " + Base64.getEncoder().encodeToString("kestra:changeme".getBytes(StandardCharsets.UTF_8));

        verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/api/v1/app-logs/search"))
            .withHeader("Authorization", equalTo(expected))
        );
    }

    @Test
    void reportsAChallengeFromAProtectedCluster(WireMockRuntimeInfo wireMock) {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("WWW-Authenticate", "Basic realm=\"quickwit\"")
                .withBody("{\"message\": \"Unauthorized\"}")
            )
        );

        var task = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        // Quickwit has no auth of its own, so this comes from a proxy in front of it: the message must
        // point at the properties that carry credentials
        assertThat(thrown.getMessage(), is("Quickwit search on index 'app-logs' failed with HTTP 401: Unauthorized"));
    }

    @Test
    void reportsAnEmptySuccessBodyAsAMisconfiguredUrl(WireMockRuntimeInfo wireMock) {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(aResponse().withStatus(200))
        );

        var task = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(thrown.getMessage(), containsString("returned an empty body"));
        assertThat(thrown.getMessage(), containsString("default port 7280"));
    }

    @Test
    void reportsAMalformedUrlBeforeSendingAnything() {
        var task = Search.builder()
            .url(Property.ofValue("not a url"))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(thrown.getMessage(), containsString("Invalid `url` `not a url`"));
    }

    @Test
    void doesNotLeakCredentialsInToString() {
        var task = Search.builder()
            .url(Property.ofValue("http://localhost:7280"))
            .basicAuth(AbstractQuickwitTask.BasicAuth.builder()
                .username(Property.ofValue("kestra"))
                .password(Property.ofValue("super-secret"))
                .build()
            )
            .headers(Property.ofValue(Map.of("Authorization", "Bearer super-secret")))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build();

        assertThat(task.toString(), org.hamcrest.Matchers.not(containsString("super-secret")));
    }
}