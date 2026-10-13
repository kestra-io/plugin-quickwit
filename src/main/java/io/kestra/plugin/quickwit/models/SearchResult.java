package io.kestra.plugin.quickwit.models;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

/**
 * Response of the Quickwit search API.
 *
 * <p>Quickwit returns {@code hits} as a list of raw documents rather than a list of wrappers
 * holding a {@code source} object, so each entry is the ingested document as-is.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#search-in-an-index">Quickwit search API</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
@NoArgsConstructor
@Schema(
    title = "Search result",
    description = "Documents matching a Quickwit search query, with the total match count and aggregations."
)
public class SearchResult {
    @Schema(
        title = "Total number of matches",
        description = "Total number of documents matching the query, regardless of pagination."
    )
    @JsonProperty("num_hits")
    private Long numHits;

    @Schema(
        title = "Matching documents",
        description = "Documents matching the query, as raw documents."
    )
    private List<Map<String, Object>> hits;

    @Schema(
        title = "Snippets",
        description = "Extracted snippets, present only when snippet fields were requested."
    )
    private List<Map<String, Object>> snippets;

    @Schema(
        title = "Processing time",
        description = "Time taken by Quickwit to process the query, in microseconds."
    )
    @JsonProperty("elapsed_time_micros")
    private Long elapsedTimeMicros;

    @Schema(
        title = "Search errors",
        description = "Non-fatal errors reported by Quickwit while executing the query."
    )
    private List<String> errors;

    @Schema(
        title = "Aggregations",
        description = "Aggregation results, present only when aggregations were requested."
    )
    private Map<String, Object> aggregations;
}