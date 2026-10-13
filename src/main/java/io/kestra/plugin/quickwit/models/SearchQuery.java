package io.kestra.plugin.quickwit.models;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The parameters of a Quickwit search, independent of whether the search is run by the
 * {@code Search} task or by the {@code Trigger}.
 *
 * @param index ID of the index, or a multi-target expression such as `app-logs*`
 * @param query query text, in the Quickwit query language
 * @param startTimestamp restricts results to {@code timestamp >= startTimestamp}, in seconds
 * @param endTimestamp restricts results to {@code timestamp < endTimestamp}, in seconds
 * @param startOffset number of documents to skip
 * @param maxHits maximum number of documents to return
 * @param searchField fields to search on when the query does not qualify a field name
 * @param snippetFields fields to extract snippets on
 * @param sortBy fields to sort the results on
 * @param aggregations aggregation request
 */
@Schema(
    title = "Search query",
    description = "Parameters of a Quickwit search request."
)
public record SearchQuery(
    String index,
    String query,
    Long startTimestamp,
    Long endTimestamp,
    Integer startOffset,
    Integer maxHits,
    List<String> searchField,
    List<String> snippetFields,
    List<String> sortBy,
    Map<String, Object> aggregations
) {
    /**
     * A search restricted to an index, a query and a hit limit, leaving every other parameter to
     * Quickwit. Used by callers that do not expose the full set of search parameters.
     */
    public static SearchQuery of(String index, String query, Integer maxHits) {
        return new SearchQuery(index, query, null, null, null, maxHits, null, null, null, null);
    }

    /**
     * Builds the JSON body of the search request.
     *
     * <p>Optional parameters are omitted when unset so Quickwit applies its own defaults, and the
     * list parameters are sent as comma-separated strings, which is what this endpoint accepts.
     *
     * @param startTimestampOverride replaces {@link #startTimestamp} when non-null; the trigger uses
     *                               this to poll the window that follows its last watermark
     */
    public Map<String, Object> toBody(Long startTimestampOverride) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", this.query);

        Long start = startTimestampOverride != null ? startTimestampOverride : this.startTimestamp;
        if (start != null) {
            body.put("start_timestamp", start);
        }
        if (this.endTimestamp != null) {
            body.put("end_timestamp", this.endTimestamp);
        }
        if (this.startOffset != null) {
            body.put("start_offset", this.startOffset);
        }
        if (this.maxHits != null) {
            body.put("max_hits", this.maxHits);
        }

        putCsv(body, "search_field", this.searchField);
        putCsv(body, "snippet_fields", this.snippetFields);
        putCsv(body, "sort_by", this.sortBy);

        if (this.aggregations != null && !this.aggregations.isEmpty()) {
            body.put("aggs", this.aggregations);
        }

        return body;
    }

    /**
     * Sends a list as the comma-separated string the search API expects.
     *
     * <p>The request body is deserialized into plain string fields, so a JSON array is rejected with
     * {@code invalid type: sequence, expected a string}. The delete task endpoint is the opposite:
     * it wants {@code search_fields} as an array, which {@code deletetask.Create} sends.
     *
     * @see <a href="https://quickwit.io/docs/reference/rest-api#search-in-an-index">Search API</a>
     */
    private static void putCsv(Map<String, Object> body, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            body.put(key, String.join(",", values));
        }
    }
}