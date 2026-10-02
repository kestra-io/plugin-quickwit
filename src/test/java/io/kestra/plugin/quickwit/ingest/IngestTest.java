package io.kestra.plugin.quickwit.ingest;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickwit.AbstractQuickwitTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IngestTest extends AbstractQuickwitTest {
    private static final String INGESTED = """
        {"num_docs_for_processing": 2, "num_ingested_docs": 2, "num_rejected_docs": 0}
        """;

    @Test
    void ingestInlineDocuments(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest")).willReturn(okJson(INGESTED)));

        var output = Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(List.of(
                Map.of("message", "first", "timestamp", 1700000000L),
                Map.of("message", "second", "timestamp", 1700000001L)
            )))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getNumDocsForProcessing(), is(2L));
        assertThat(output.getNumIngestedDocs(), is(2L));
        assertThat(output.getNumRejectedDocs(), is(0L));
        assertThat(output.getParseFailures(), is(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void sendsOneJsonDocumentPerLine(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest")).willReturn(okJson(INGESTED)));

        Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(List.of(
                Map.of("message", "first"),
                Map.of("message", "second")
            )))
            .build()
            .run(runContextFactory.of());

        // NDJSON: one compact document per line, which is the only format the ingest API accepts
        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/ingest"))
            .withRequestBody(equalTo("""
                {"message":"first"}
                {"message":"second"}"""))
        );
    }

    @Test
    void sendsTheCommitAndDetailedResponseOptions(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest")).willReturn(okJson(INGESTED)));

        Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(Map.of("message", "first")))
            .commit(Property.ofValue(Commit.WAIT_FOR))
            .detailedResponse(Property.ofValue(true))
            .build()
            .run(runContextFactory.of());

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/ingest"))
            .withQueryParam("commit", equalTo("wait_for"))
            .withQueryParam("detailed_response", equalTo("true"))
        );
    }

    @Test
    void sendsForceAsTheCommitOption(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest")).willReturn(okJson(INGESTED)));

        Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(Map.of("message", "first")))
            .commit(Property.ofValue(Commit.FORCE))
            .build()
            .run(runContextFactory.of());

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/ingest"))
            .withQueryParam("commit", equalTo("force"))
        );
    }

    @Test
    void ingestsASingleInlineDocument(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest")).willReturn(okJson("{\"num_docs_for_processing\": 1, \"num_ingested_docs\": 1, \"num_rejected_docs\": 0}")));

        var output = Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(Map.of("message", "only one")))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getNumIngestedDocs(), is(1L));

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/ingest"))
            .withRequestBody(equalToJson("{\"message\": \"only one\"}"))
        );
    }

    @Test
    void ingestsAJsonString(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest")).willReturn(okJson(INGESTED)));

        var output = Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue("[{\"message\": \"one\"}, {\"message\": \"two\"}]"))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getNumIngestedDocs(), is(2L));
    }

    @Test
    void reportsRejectedDocuments(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest")).willReturn(okJson("""
            {
              "num_docs_for_processing": 2,
              "num_ingested_docs": 1,
              "num_rejected_docs": 1,
              "parse_failures": [{"message": "unknown field", "reason": "invalid_schema", "document": "{\\"oops\\":1}"}]
            }
            """)));

        var output = Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(List.of(Map.of("message", "a"), Map.of("oops", 1))))
            .detailedResponse(Property.ofValue(true))
            .build()
            .run(runContextFactory.of());

        assertThat(output.getNumIngestedDocs(), is(1L));
        assertThat(output.getNumRejectedDocs(), is(1L));
        assertThat(output.getParseFailures(), hasSize(1));
        assertThat(output.getParseFailures().getFirst().getReason(), is("invalid_schema"));
        assertThat(output.getParseFailures().getFirst().getMessage(), is("unknown field"));
    }

    @Test
    void readsDocumentsFromKestraInternalStorage(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest")).willReturn(okJson(INGESTED)));

        var runContext = runContextFactory.of();
        var uri = runContext.storage().putFile(
            java.nio.file.Path.of(
                writeTemporary(runContext, "{\"message\": \"from a file\"}\n{\"message\": \"second line\"}\n")
            ).toFile()
        );

        var output = Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(uri.toString()))
            .build()
            .run(runContext);

        assertThat(output.getNumIngestedDocs(), is(2L));

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/ingest"))
            .withRequestBody(equalTo("""
                {"message":"from a file"}
                {"message":"second line"}"""))
        );
    }

    @Test
    void rejectsAnEmptyDocumentSet(WireMockRuntimeInfo wireMock) {
        var task = Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(List.of()))
            .build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));
        assertThat(thrown.getMessage(), containsString("resolved to no document"));
    }

    @Test
    void surfacesTheQuickwitError(WireMockRuntimeInfo wireMock) {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/ingest"))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"doc mapping does not contain field 'timestamp'\"}")
            )
        );

        var task = Ingest.builder()
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .from(Property.ofValue(Map.of("message", "no timestamp")))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of()));

        assertThat(
            thrown.getMessage(),
            is("Quickwit ingest into index 'app-logs' failed with HTTP 400: doc mapping does not contain field 'timestamp'")
        );
    }

    private String writeTemporary(io.kestra.core.runners.RunContext runContext, String content) throws Exception {
        var file = runContext.workingDir().createTempFile(".ndjson").toFile();
        java.nio.file.Files.writeString(file.toPath(), content);

        return file.getAbsolutePath();
    }
}