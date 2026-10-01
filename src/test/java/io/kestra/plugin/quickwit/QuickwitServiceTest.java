package io.kestra.plugin.quickwit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.models.SearchQuery;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests of the endpoint, request and error-message helpers, which need no HTTP server.
 */
class QuickwitServiceTest extends AbstractQuickwitTest {
    @Test
    void endpointPrefixesTheApiVersion() {
        assertThat(QuickwitService.endpoint("http://localhost:7280", "indexes/app-logs").toString(), is("http://localhost:7280/api/v1/indexes/app-logs"));
    }

    @Test
    void endpointToleratesTrailingAndMissingSlashes() {
        assertThat(QuickwitService.endpoint("http://localhost:7280/", "/indexes").toString(), is("http://localhost:7280/api/v1/indexes"));
        assertThat(QuickwitService.endpoint("http://localhost:7280", "indexes").toString(), is("http://localhost:7280/api/v1/indexes"));
    }

    @Test
    void endpointUrlEncodesTheQuery() {
        var uri = QuickwitService.endpoint("http://localhost:7280", "app-logs/search", Map.of("query", "severity:ERROR AND body:\"a b\""));

        assertThat(uri.toString(), is("http://localhost:7280/api/v1/app-logs/search?query=severity%3AERROR+AND+body%3A%22a+b%22"));
    }

    @Test
    void endpointSkipsNullQueryParameters() {
        // Map.of() rejects null values, so the nullable case is built by hand
        Map<String, Object> query = new HashMap<>();
        query.put("commit", null);

        assertThat(QuickwitService.endpoint("http://localhost:7280", "indexes", query).toString(), is("http://localhost:7280/api/v1/indexes"));
    }

    @Test
    void pathSegmentEncodesIndexIds() {
        // percent-encoded, not '+': inside a URI path a plus sign is a literal plus
        assertThat(QuickwitService.pathSegment("app logs/v2"), is("app%20logs%2Fv2"));
    }

    @Test
    void renderedUrlRejectsBlankValues() {
        RunContext runContext = runContextFactory.of();

        var thrown = assertThrows(IllegalArgumentException.class, () -> QuickwitService.renderedUrl(runContext, null));
        assertThat(thrown.getMessage(), containsString("`url` is required"));
    }

    @Test
    void renderedUrlRejectsUrisWithoutScheme() {
        RunContext runContext = runContextFactory.of();

        var thrown = assertThrows(IllegalArgumentException.class, () -> QuickwitService.renderedUrl(runContext, Property.ofValue("localhost:7280")));
        assertThat(thrown.getMessage(), containsString("expected a URI with a scheme and a host"));
    }

    @Test
    void renderedUrlStripsTheTrailingSlash() throws IllegalVariableEvaluationException {
        RunContext runContext = runContextFactory.of();

        assertThat(QuickwitService.renderedUrl(runContext, Property.ofValue("http://localhost:7280/")), is("http://localhost:7280"));
    }

    @Test
    void quickwitMessageExtractsTheQuickwitError() {
        assertThat(QuickwitService.quickwitMessage("{\"message\":\"Failed to parse query\"}"), is("Failed to parse query"));
    }

    @Test
    void quickwitMessageHandlesHtmlErrorPages() {
        // a proxy or gateway may answer with HTML rather than Quickwit's JSON error object
        assertThat(QuickwitService.quickwitMessage("<html>Bad Gateway</html>"), containsString("Bad Gateway"));
    }

    @Test
    void quickwitMessageHandlesAnAbsentBody() {
        assertThat(QuickwitService.quickwitMessage(""), is("no response body"));
    }

    @Test
    void requestCarriesTheCustomHeaders() {
        var request = QuickwitService
            .request("GET", QuickwitService.endpoint("http://localhost:7280", "cluster"), Map.of("X-Api-Key", "abc"), null)
            .build();

        assertThat(request.getMethod(), is("GET"));
        assertThat(request.getHeaders().firstValue("X-Api-Key").orElseThrow(), is("abc"));
        assertThat(request.getHeaders().firstValue("Accept").orElseThrow(), is("application/json"));
        assertThat(request.getBody(), is(nullValue()));
    }

    @Test
    void httpConfigurationKeepsTheDefaultReadTimeoutWhenUnset() throws IllegalVariableEvaluationException {
        var configuration = QuickwitService.httpConfiguration(runContextFactory.of(), null, null, null);

        // a zero or absent read timeout would break long searches and commit=force ingests
        assertThat(configuration.getTimeout().getReadIdleTimeout(), is(notNullValue()));
    }

    @Test
    void httpConfigurationAllowsFailedResponsesSoErrorsCanBeReported() throws IllegalVariableEvaluationException {
        var configuration = QuickwitService.httpConfiguration(runContextFactory.of(), null, null, null);

        // handled by QuickwitService#execute, which can then surface Quickwit's own message
        assertThat(runContextFactory.of().render(configuration.getAllowFailed()).as(Boolean.class).orElseThrow(), is(true));
    }

    @Test
    void httpConfigurationCarriesBasicAuth() throws IllegalVariableEvaluationException {
        var basicAuth = AbstractQuickwitTask.BasicAuth.builder()
            .username(Property.ofValue("kestra"))
            .password(Property.ofValue("changeme"))
            .build();

        var configuration = QuickwitService.httpConfiguration(runContextFactory.of(), null, null, basicAuth);

        assertThat(configuration.getAuth(), is(notNullValue()));
    }

    @Test
    void searchQueryOmitsUnsetOptionalParameters() {
        var body = SearchQuery.of("app-logs", "severity:ERROR", null).toBody(null);

        assertThat(body, is(Map.of("query", "severity:ERROR")));
    }

    @Test
    void searchQueryKeepsListsAsJsonArrays() {
        var body = new SearchQuery(
            "app-logs",
            "*",
            100L,
            200L,
            0,
            10,
            List.of("message"),
            null,
            List.of("timestamp"),
            Map.of("severity", Map.of("type", "terms"))
        ).toBody(null);

        assertThat(body.get("start_timestamp"), is(100L));
        assertThat(body.get("end_timestamp"), is(200L));
        assertThat(body.get("start_offset"), is(0));
        assertThat(body.get("max_hits"), is(10));
        assertThat(body.get("search_field"), is(List.of("message")));
        assertThat(body.get("sort_by"), is(List.of("timestamp")));
        assertThat(body.get("aggs"), is(Map.of("severity", Map.of("type", "terms"))));
    }

    @Test
    void searchQueryOverrideReplacesTheStartTimestamp() {
        var query = new SearchQuery("app-logs", "*", 100L, null, null, null, null, null, null, null);

        assertThat(query.toBody(500L).get("start_timestamp"), is(500L));
    }
}