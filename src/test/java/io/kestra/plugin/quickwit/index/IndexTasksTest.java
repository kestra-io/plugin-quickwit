package io.kestra.plugin.quickwit.index;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickwit.AbstractQuickwitTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Happy-path and error coverage for every task of the index management package.
 */
class IndexTasksTest extends AbstractQuickwitTest {
    private static final String INDEX_METADATA = """
        {
          "version": "0.8",
          "index_uid": "app-logs:01H000000000000000000",
          "create_timestamp": 1700000000,
          "index_config": {
            "index_id": "app-logs",
            "doc_mapping": {"timestamp_field": "timestamp"},
            "search_settings": {"default_search_fields": ["message"]}
          },
          "checkpoint": {"_ingest-api-source": {"source_id": "_ingest-api-source"}},
          "sources": []
        }
        """;

    private static final Map<String, Object> DOC_MAPPING = Map.of(
        "timestamp_field", "timestamp",
        "field_mappings", List.of(
            Map.of("name", "timestamp", "type", "datetime", "fast", true),
            Map.of("name", "message", "type", "text")
        )
    );

    @Test
    void create(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/indexes")).willReturn(okJson(INDEX_METADATA)));

        var output = Create.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .configVersion(Property.ofValue("0.8"))
            .docMapping(Property.ofValue(DOC_MAPPING))
            .searchSettings(Property.ofValue(Map.of("default_search_fields", List.of("message"))))
            .retention(Property.ofValue(Map.of("period", "30 days", "schedule", "@daily")))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getIndexUid(), is("app-logs:01H000000000000000000"));
        assertThat(output.getCreateTimestamp(), is(1700000000L));
        assertThat(output.getMetadata(), is(notNullValue()));
        assertThat(output.getMetadata().getIndexConfig().get("index_id"), is("app-logs"));
    }

    @Test
    void createSendsTheIndexConfiguration(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/indexes")).willReturn(okJson(INDEX_METADATA)));

        Create.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .configVersion(Property.ofValue("0.8"))
            .docMapping(Property.ofValue(DOC_MAPPING))
            .build()
            .run(runContextFactory.of());

        verify(postRequestedFor(urlPathEqualTo("/api/v1/indexes"))
            .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.equalToJson("""
                {
                  "version": "0.8",
                  "index_id": "app-logs",
                  "doc_mapping": {
                    "timestamp_field": "timestamp",
                    "field_mappings": [
                      {"name": "timestamp", "type": "datetime", "fast": true},
                      {"name": "message", "type": "text"}
                    ]
                  }
                }
                """))
        );
    }

    @Test
    void createRejectsAnEmptyDocMapping(WireMockRuntimeInfo wireMock) {
        var task = Create.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .docMapping(Property.ofValue(Map.of()))
            .build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(thrown.getMessage(), containsString("`docMapping` is required"));
    }

    @Test
    void readsIndex(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/indexes/app-logs")).willReturn(okJson(INDEX_METADATA)));

        var output = Get.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getIndexUid(), is("app-logs:01H000000000000000000"));
        assertThat(output.getMetadata().getIndexConfig().get("search_settings"), is(notNullValue()));

        verify(getRequestedFor(urlPathEqualTo("/api/v1/indexes/app-logs")));
    }

    @Test
    void getUrlEncodesTheIndexId(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/indexes/app%20logs")).willReturn(okJson(INDEX_METADATA)));

        Get.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app logs"))
            .build()
            .run(runContextFactory.of());

        verify(getRequestedFor(urlPathEqualTo("/api/v1/indexes/app%20logs")));
    }

    @Test
    void listsIndexes(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/indexes")).willReturn(okJson("[" + INDEX_METADATA + "]")));

        var output = io.kestra.plugin.quickwit.index.List.builder()
            .url(Property.ofValue(url(wireMock)))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getSize(), is(1));
        assertThat(output.getIndexes(), hasSize(1));
        assertThat(output.getIndexes().getFirst().getIndexUid(), is("app-logs:01H000000000000000000"));
    }

    @Test
    void listHandlesAnEmptyCluster(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/indexes")).willReturn(okJson("[]")));

        var output = io.kestra.plugin.quickwit.index.List.builder()
            .url(Property.ofValue(url(wireMock)))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getSize(), is(0));
        assertThat(output.getIndexes(), hasSize(0));
    }

    @Test
    void deletesIndex(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(delete(urlPathEqualTo("/api/v1/indexes/app-logs")).willReturn(okJson("""
            [
              {
                "split_id": "01GK1XNAECH7P14850S9VV6P94",
                "num_docs": 1337,
                "uncompressed_docs_size_bytes": 23933408,
                "file_name": "01GK1XNAECH7P14850S9VV6P94.split",
                "file_size_bytes": 2991676
              }
            ]
            """)));

        var output = Delete.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getSize(), is(1));
        assertThat(output.getDeletedSplits(), hasSize(1));
        assertThat(output.getDeletedSplits().getFirst().getSplitId(), is("01GK1XNAECH7P14850S9VV6P94"));
        assertThat(output.getDeletedSplits().getFirst().getNumDocs(), is(1337L));

        verify(deleteRequestedFor(urlPathEqualTo("/api/v1/indexes/app-logs")));
    }

    @Test
    void clearAcceptsTheEmptyBodyQuickwitReturns(WireMockRuntimeInfo wireMock) throws Exception {
        // Quickwit documents the clear endpoint as returning an empty body
        stubFor(put(urlPathEqualTo("/api/v1/indexes/app-logs/clear")).willReturn(aResponse().withStatus(200)));

        var output = Clear.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));

        verify(putRequestedFor(urlPathEqualTo("/api/v1/indexes/app-logs/clear")));
    }

    @Test
    void surfacesTheQuickwitErrorOnAMissingIndex(WireMockRuntimeInfo wireMock) {
        stubFor(get(urlPathEqualTo("/api/v1/indexes/unknown"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Index unknown does not exist\"}")
            )
        );

        var task = Get.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("unknown"))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(thrown.getMessage(), is("Quickwit read of index 'unknown' failed with HTTP 404: Index unknown does not exist"));
    }

    @Test
    void surfacesAnHtmlErrorPageFromAProxy(WireMockRuntimeInfo wireMock) {
        stubFor(get(urlPathEqualTo("/api/v1/indexes/app-logs"))
            .willReturn(aResponse().withStatus(502).withBody("<html><body>502 Bad Gateway</body></html>"))
        );

        var task = Get.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(thrown.getMessage(), containsString("failed with HTTP 502"));
        assertThat(thrown.getMessage(), containsString("502 Bad Gateway"));
    }
}