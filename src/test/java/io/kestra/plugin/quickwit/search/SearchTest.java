package io.kestra.plugin.quickwit.search;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.AbstractQuickwitTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchTest extends AbstractQuickwitTest {
    private static final String SEARCH_RESPONSE = """
        {
          "num_hits": 2,
          "elapsed_time_micros": 1234,
          "hits": [
            {"message": "payment gateway timeout", "severity": "ERROR"},
            {"message": "payment gateway timeout", "severity": "ERROR"}
          ]
        }
        """;

    @Test
    void fetch(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson(SEARCH_RESPONSE)));

        var output = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:ERROR"))
            .maxHits(Property.ofValue(100))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getTotal(), is(2L));
        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getElapsedTimeMicros(), is(1234L));
        assertThat(output.getRow(), is(nullValue()));
        assertThat(output.getUri(), is(nullValue()));
    }

    @Test
    void sendsTheSearchBodyAsJson(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson(SEARCH_RESPONSE)));

        Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:ERROR"))
            .startTimestamp(Property.ofValue(1000L))
            .endTimestamp(Property.ofValue(2000L))
            .startOffset(Property.ofValue(5))
            .maxHits(Property.ofValue(100))
            .searchField(Property.ofValue(List.of("message", "severity")))
            .snippetFields(Property.ofValue(List.of("message")))
            .sortBy(Property.ofValue(List.of("timestamp")))
            .aggregations(Property.ofValue(Map.of("severity", Map.of("type", "terms"))))
            .build()
            .run(runContextFactory.of());

        // POST carries aggs as a nested object, which the query string form of the GET endpoint cannot
        // express. The list parameters are comma-separated strings: the search API deserializes them
        // into plain string fields and rejects a JSON array with
        // `invalid type: sequence, expected a string`. Note the singular `search_field` here, against
        // the plural `search_fields` of the delete task API.
        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/search"))
            .withRequestBody(equalToJson("""
                {
                  "query": "severity:ERROR",
                  "start_timestamp": 1000,
                  "end_timestamp": 2000,
                  "start_offset": 5,
                  "max_hits": 100,
                  "search_field": "message,severity",
                  "snippet_fields": "message",
                  "sort_by": "timestamp",
                  "aggs": {"severity": {"type": "terms"}}
                }
                """))
        );
    }

    @Test
    void fetchOne(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson(SEARCH_RESPONSE)));

        var output = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:ERROR"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getRow(), is(notNullValue()));
        assertThat(output.getRow().get("severity"), is("ERROR"));
        assertThat(output.getRows(), is(nullValue()));
        assertThat(output.getTotal(), is(2L));
    }

    @Test
    void store(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson(SEARCH_RESPONSE)));

        var runContext = runContextFactory.of();

        var output = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:ERROR"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build()
            .run(runContext);

        assertThat(output.getUri(), is(notNullValue()));
        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), is(nullValue()));

        try (var input = runContext.storage().getFile(output.getUri())) {
            var stored = io.kestra.core.serializers.FileSerde.readAll(input, Map.class).collectList().block();
            assertThat(stored, hasSize(2));
        }
    }

    @Test
    void none(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson(SEARCH_RESPONSE)));

        var output = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:ERROR"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getSize(), is(0));
        assertThat(output.getRows(), is(nullValue()));
        assertThat(output.getRow(), is(nullValue()));
    }

    @Test
    void aggregations(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(okJson("""
                {"num_hits": 1, "elapsed_time_micros": 7, "hits": [], "aggregations": {"severity": {"buckets": []}}}
                """)));

        var output = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:ERROR"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getTotal(), is(1L));
        assertThat(output.getSize(), is(0));
        assertThat(output.getAggregations(), is(notNullValue()));
        assertThat(output.getAggregations().get("severity"), is(notNullValue()));
    }

    @Test
    void rendersTemplatedProperties(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/prod-logs/search"))
            .willReturn(okJson("{\"num_hits\": 0, \"hits\": []}")));

        RunContext runContext = runContextFactory.of(Map.of("index", "prod-logs", "severity", "WARN"));

        var output = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofExpression("{{ index }}"))
            .query(Property.ofExpression("severity:{{ severity }}"))
            .build()
            .run(runContext);

        assertThat(output.getTotal(), is(0L));
        verify(postRequestedFor(urlPathEqualTo("/api/v1/prod-logs/search"))
            .withRequestBody(equalToJson("{\"query\": \"severity:WARN\"}"))
        );
    }

    @Test
    void surfacesTheQuickwitError(WireMockRuntimeInfo wireMock) {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Failed to parse query\"}")
            )
        );

        var task = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:::"))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(thrown.getMessage(), is("Quickwit search on index 'app-logs' failed with HTTP 400: Failed to parse query"));
    }

    @Test
    void reportsAnUnusableBody(WireMockRuntimeInfo wireMock) {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/api/v1/app-logs/search"))
            .willReturn(aResponse().withStatus(200).withBody("not json at all"))
        );

        var task = Search.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("*"))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("not valid JSON"));
    }

    @Test
    void rejectsABlankIndex() throws IllegalVariableEvaluationException {
        var task = Search.builder()
            .url(Property.ofValue("http://localhost:7280"))
            .index(Property.ofValue("  "))
            .query(Property.ofValue("*"))
            .build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(thrown.getMessage(), is("`index` is required and cannot be blank"));
    }
}