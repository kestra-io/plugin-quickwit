package io.kestra.plugin.quickwit.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

/**
 * Response of the Quickwit ingest API.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#ingest-data-into-an-index">Quickwit ingest API</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
@NoArgsConstructor
@Schema(
    title = "Ingest result",
    description = "Counters reported by Quickwit after a batch of documents was submitted."
)
public class IngestResult {
    @Schema(
        title = "Documents submitted",
        description = "Total number of documents submitted for processing. They may not have been indexed yet."
    )
    @JsonProperty("num_docs_for_processing")
    private Long numDocsForProcessing;

    @Schema(
        title = "Documents ingested",
        description = "Number of documents successfully persisted in the write ahead log."
    )
    @JsonProperty("num_ingested_docs")
    private Long numIngestedDocs;

    @Schema(
        title = "Documents rejected",
        description = "Number of documents that could not be parsed, for example because of invalid JSON or a doc mapping mismatch."
    )
    @JsonProperty("num_rejected_docs")
    private Long numRejectedDocs;

    @Schema(
        title = "Parse failures",
        description = "Details of the rejected documents, present only when `detailedResponse` is enabled."
    )
    @JsonProperty("parse_failures")
    private List<ParseFailure> parseFailures;

    @Schema(
        title = "Parse failure",
        description = "A document rejected by the ingest API, with the reason and the offending payload."
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @Builder
    @Jacksonized
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ParseFailure {
        @Schema(
            title = "Failure message",
            description = "Human readable explanation of why the document was rejected."
        )
        private String message;

        @Schema(
            title = "Failure reason",
            description = "Machine readable reason, one of `invalid_json`, `invalid_schema` or `unspecified`."
        )
        private String reason;

        @Schema(
            title = "Rejected document",
            description = "The UTF-8 decoded document chunk that generated the error."
        )
        private String document;
    }
}