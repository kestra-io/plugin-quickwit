package io.kestra.plugin.quickwit.ingest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.quickwit.AbstractQuickwitTask;
import io.kestra.plugin.quickwit.models.IngestResult;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Sends a batch of documents to a Quickwit index.
 *
 * <p>Quickwit only accepts NDJSON on this endpoint, so whatever shape the documents arrive in (a Kestra
 * file, an inline list, a JSON string) they are serialized line by line here.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#ingest-data-into-an-index">Quickwit ingest API</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Ingest documents into Quickwit",
    description = """
        Sends documents to a Quickwit index as a single NDJSON batch.

        The batch is limited to 10 MB by default, so split larger datasets across several runs or use a
        Quickwit source instead.
        """
)
@Plugin(
    metrics = {
        @Metric(name = "records", type = Counter.TYPE, unit = "records", description = "Number of documents sent")
    },
    examples = {
        @Example(
            title = "Ingest a file produced by a previous task",
            full = true,
            code = """
                id: quickwit_ingest
                namespace: company.team

                inputs:
                  - id: file
                    type: FILE

                tasks:
                  - id: ingest
                    type: io.kestra.plugin.quickwit.ingest.Ingest
                    url: "https://quickwit.example.com:7280"
                    index: app-logs
                    from: "{{ inputs.file }}"
                    commit: WAIT_FOR
                """
        ),
        @Example(
            title = "Ingest inline documents and check for rejections",
            full = true,
            code = """
                id: quickwit_ingest_inline
                namespace: company.team

                tasks:
                  - id: ingest
                    type: io.kestra.plugin.quickwit.ingest.Ingest
                    url: "http://localhost:7280"
                    index: app-logs
                    commit: FORCE
                    detailedResponse: true
                    from:
                      - timestamp: "{{ now() | date('X') }}"
                        service: checkout
                        severity: ERROR
                        message: "Payment gateway timeout"
                      - timestamp: "{{ now() | date('X') }}"
                        service: checkout
                        severity: ERROR
                        message: "Payment gateway timeout"

                  - id: check
                    type: io.kestra.plugin.core.log.Log
                    message: "Ingested {{ outputs.ingest.numIngestedDocs }} / rejected {{ outputs.ingest.numRejectedDocs }}"
                """
        )
    }
)
public class Ingest extends AbstractQuickwitTask implements RunnableTask<Ingest.Output> {
    private static final ObjectMapper JSON = JacksonMapper.ofJson();

    @Schema(
        title = "Index ID",
        description = "ID of the index to ingest into. The ingest API is only served by nodes running an indexer."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> index;

    @Schema(
        title = "Documents",
        description = """
            Documents to send, as one of:

            - a single document, as a map;
            - a list of documents, as a list of maps;
            - a URI, either `kestra://` internal storage or a local `file://` path;
            - a JSON string holding one document or a list of documents.

            Documents must match the doc mapping of the index.
            """
    )
    @NotNull
    @PluginProperty(dynamic = true, group = "main")
    private Property<Object> from;

    @Schema(
        title = "Commit behavior",
        description = """
            When the ingested documents become visible to search: `AUTO` (default), `WAIT_FOR` or `FORCE`.
            Use `FORCE` when a search runs right after this task and must already see the documents.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Commit> commit = Property.ofValue(Commit.AUTO);

    @Schema(
        title = "Detailed response",
        description = """
            Ask Quickwit to describe every rejected document in `parseFailures`.
            Handy to diagnose a doc mapping mismatch, but it costs performance.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> detailedResponse = Property.ofValue(false);

    @Override
    public Ingest.Output run(RunContext runContext) throws Exception {
        String renderedIndex = required(runContext, this.index, "index");

        String ndjson = toNdjson(runContext);
        if (ndjson.isEmpty()) {
            throw new IllegalArgumentException("`from` resolved to no document, nothing to ingest");
        }

        Commit commit = runContext.render(this.commit).as(Commit.class).orElse(Commit.AUTO);
        boolean detailed = runContext.render(this.detailedResponse).as(Boolean.class).orElse(false);

        HttpRequest request = request(
            runContext,
            "POST",
            pathSegment(renderedIndex) + "/ingest",
            Map.of(
                "commit", commit.name().toLowerCase(java.util.Locale.ROOT),
                "detailed_response", detailed
            ),
            HttpRequest.StringRequestBody.builder()
                // the ingest handler reads raw body bytes and never inspects the content type;
                // application/json is what Quickwit's own OpenAPI document declares for this endpoint
                .contentType("application/json")
                .charset(StandardCharsets.UTF_8)
                .content(ndjson)
                .build()
        ).build();

        IngestResult result;
        try (var client = client(runContext)) {
            result = execute(client, request, IngestResult.class, "ingest into index '" + renderedIndex + "'");
        }

        long ingested = orZero(result.getNumIngestedDocs());
        long rejected = orZero(result.getNumRejectedDocs());
        runContext.metric(Counter.of("records", ingested + rejected));

        if (rejected > 0) {
            runContext.logger().warn("Quickwit rejected {} document(s) out of {} submitted to index '{}'", rejected, orZero(result.getNumDocsForProcessing()), renderedIndex);
        }

        return Output.builder()
            .commit(commit)
            .numDocsForProcessing(orZero(result.getNumDocsForProcessing()))
            .numIngestedDocs(ingested)
            .numRejectedDocs(rejected)
            .parseFailures(result.getParseFailures())
            .build();
    }

    /**
     * Reads {@link #from} and serializes the documents as NDJSON, the only payload format Quickwit
     * accepts on the ingest API.
     */
    private String toNdjson(RunContext runContext) throws IllegalVariableEvaluationException {
        Object source = runContext.render(this.from).as(Object.class).orElse(null);

        return Data.from(source)
            .read(runContext)
            .map(Ingest::toJson)
            .collectList()
            .blockOptional()
            .orElse(List.of())
            .stream()
            .collect(Collectors.joining("\n"));
    }

    private static String toJson(Map<String, Object> document) {
        try {
            return JSON.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize a document as JSON: " + e.getMessage(), e);
        }
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Commit behavior",
            description = "The commit behavior applied to this ingest call."
        )
        private Commit commit;

        @Schema(
            title = "Documents submitted",
            description = "Number of documents submitted for processing. They may not be searchable yet."
        )
        private Long numDocsForProcessing;

        @Schema(
            title = "Documents ingested",
            description = "Number of documents successfully persisted in the write ahead log."
        )
        private Long numIngestedDocs;

        @Schema(
            title = "Documents rejected",
            description = "Number of documents that could not be parsed, usually because of a doc mapping mismatch."
        )
        private Long numRejectedDocs;

        @Schema(
            title = "Parse failures",
            description = "Details of the rejected documents, present only when `detailedResponse` is enabled."
        )
        private List<IngestResult.ParseFailure> parseFailures;
    }
}