package io.kestra.plugin.quickwit.source;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickwit.AbstractQuickwitTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Happy-path and error coverage for every task of the source management package.
 */
class SourceTasksTest extends AbstractQuickwitTest {
    private static final String SOURCE = """
        {
          "version": "0.8",
          "source_id": "kafka-source",
          "source_type": "kafka",
          "num_pipelines": 2,
          "params": {"topic": "app-logs", "client_params": {"bootstrap.servers": "kafka:9092"}}
        }
        """;

    private static final Map<String, Object> PARAMS = Map.of(
        "topic", "app-logs",
        "client_params", Map.of("bootstrap.servers", "kafka:9092")
    );

    @Test
    void create(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/indexes/app-logs/sources")).willReturn(okJson(SOURCE)));

        var output = Create.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .source(Property.ofValue("kafka-source"))
            .configVersion(Property.ofValue("0.8"))
            .sourceType(Property.ofValue("kafka"))
            .numPipelines(Property.ofValue(2))
            .params(Property.ofValue(PARAMS))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getSource().getSourceId(), is("kafka-source"));
        assertThat(output.getSource().getSourceType(), is("kafka"));
        assertThat(output.getSource().getNumPipelines(), is(2));
        assertThat(output.getSource().getParams().get("topic"), is("app-logs"));

        verify(postRequestedFor(urlPathEqualTo("/api/v1/indexes/app-logs/sources"))
            .withRequestBody(equalToJson("""
                {
                  "version": "0.8",
                  "source_id": "kafka-source",
                  "source_type": "kafka",
                  "params": {"topic": "app-logs", "client_params": {"bootstrap.servers": "kafka:9092"}},
                  "num_pipelines": 2
                }
                """))
        );
    }

    @Test
    void createRejectsEmptyParams(WireMockRuntimeInfo wireMock) {
        var task = Create.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .source(Property.ofValue("kafka-source"))
            .sourceType(Property.ofValue("kafka"))
            .params(Property.ofValue(Map.of()))
            .build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(thrown.getMessage(), containsString("`params` is required"));
    }

    @Test
    void toggleEnableAcceptsTheEmptyBodyQuickwitReturns(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(put(urlPathEqualTo("/api/v1/indexes/app-logs/sources/kafka-source/toggle"))
            .willReturn(aResponse().withStatus(200))
        );

        var output = Toggle.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .source(Property.ofValue("kafka-source"))
            .enable(Property.ofValue(true))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getSource(), is("kafka-source"));
        assertThat(output.getEnable(), is(true));

        verify(putRequestedFor(urlPathEqualTo("/api/v1/indexes/app-logs/sources/kafka-source/toggle"))
            .withRequestBody(equalToJson("{\"enable\": true}"))
        );
    }

    @Test
    void toggleDisable(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(put(urlPathEqualTo("/api/v1/indexes/app-logs/sources/kafka-source/toggle"))
            .willReturn(aResponse().withStatus(200))
        );

        var output = Toggle.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .source(Property.ofValue("kafka-source"))
            .enable(Property.ofValue(false))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getEnable(), is(false));

        verify(putRequestedFor(urlPathEqualTo("/api/v1/indexes/app-logs/sources/kafka-source/toggle"))
            .withRequestBody(equalToJson("{\"enable\": false}"))
        );
    }

    @Test
    void resetCheckpointAcceptsTheEmptyBodyQuickwitReturns(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(put(urlPathEqualTo("/api/v1/indexes/app-logs/sources/kafka-source/reset-checkpoint"))
            .willReturn(aResponse().withStatus(200))
        );

        var output = ResetCheckpoint.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .source(Property.ofValue("kafka-source"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getSource(), is("kafka-source"));

        verify(putRequestedFor(urlPathEqualTo("/api/v1/indexes/app-logs/sources/kafka-source/reset-checkpoint")));
    }

    @Test
    void deletesSource(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.delete(urlPathEqualTo("/api/v1/indexes/app-logs/sources/kafka-source"))
            .willReturn(aResponse().withStatus(200))
        );

        var output = Delete.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .source(Property.ofValue("kafka-source"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getIndex(), is("app-logs"));
        assertThat(output.getSource(), is("kafka-source"));

        verify(deleteRequestedFor(urlPathEqualTo("/api/v1/indexes/app-logs/sources/kafka-source")));
    }

    @Test
    void surfacesTheQuickwitErrorOnAnUnknownSource(WireMockRuntimeInfo wireMock) {
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.delete(urlPathEqualTo("/api/v1/indexes/app-logs/sources/nope"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Source nope does not exist\"}")
            )
        );

        var task = Delete.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .source(Property.ofValue("nope"))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(
            thrown.getMessage(),
            is("Quickwit deletion of source 'nope' on index 'app-logs' failed with HTTP 404: Source nope does not exist")
        );
    }

    @Test
    void rejectsABlankSourceId() throws Exception {
        var task = Toggle.builder()
            .url(Property.ofValue("http://localhost:7280"))
            .index(Property.ofValue("app-logs"))
            .source(Property.ofValue(""))
            .enable(Property.ofValue(true))
            .build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(thrown.getMessage(), is("`source` is required and cannot be blank"));
        assertThat(thrown.getMessage(), is(org.hamcrest.Matchers.notNullValue()));
    }
}