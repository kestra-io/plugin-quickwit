package io.kestra.plugin.quickwit.search;

import java.util.List;
import java.util.Map;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.AbstractQuickwitTask;
import io.kestra.plugin.quickwit.QuickwitService;
import io.kestra.plugin.quickwit.models.SearchQuery;
import io.kestra.plugin.quickwit.models.SearchResult;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Query parameters shared by the {@link Search} task and the {@link Trigger}.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#search-in-an-index">Quickwit search API</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQuickwitSearch extends AbstractQuickwitTask {
    @Schema(
        title = "Index ID",
        description = """
            ID of the index to search.

            Quickwit also accepts a multi-target expression such as `app-logs-000001,app-logs-000002`
            or a wildcard pattern such as `app-logs*`.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> index;

    @Schema(
        title = "Query",
        description = """
            Query text, using the [Quickwit query language](https://quickwit.io/docs/reference/query-language),
            for example `severity:ERROR`.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> query;

    @Schema(
        title = "Start timestamp",
        description = """
            Restricts the search to documents with `timestamp >= startTimestamp`, in seconds.
            Taking advantage of this lets Quickwit prune splits.
            """
    )
    @PluginProperty(group = "main")
    private Property<Long> startTimestamp;

    @Schema(
        title = "End timestamp",
        description = """
            Restricts the search to documents with `timestamp < endTimestamp`, in seconds.
            Taking advantage of this lets Quickwit prune splits.
            """
    )
    @PluginProperty(group = "main")
    private Property<Long> endTimestamp;

    @Schema(
        title = "Start offset",
        description = "Number of documents to skip. Quickwit defaults to `0`."
    )
    @PluginProperty(group = "processing")
    private Property<Integer> startOffset;

    @Schema(
        title = "Maximum number of hits",
        description = "Maximum number of documents to return. Quickwit defaults to `20`."
    )
    @PluginProperty(group = "processing")
    private Property<Integer> maxHits;

    @Schema(
        title = "Search fields",
        description = """
            Fields to search on when the query does not qualify a field name.
            Defaults to the `default_search_fields` of the index configuration.
            """
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> searchField;

    @Schema(
        title = "Snippet fields",
        description = "Fields to extract snippets on. Snippets are returned in the `snippets` output."
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> snippetFields;

    @Schema(
        title = "Sort by",
        description = """
            Fields to sort the results on. You can sort by up to two fast fields, or by the BM25 `_score`.
            By default Quickwit returns the most recent documents first.
            """
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> sortBy;

    @Schema(
        title = "Aggregations",
        description = """
            Aggregation request, using the
            [aggregations syntax](https://quickwit.io/docs/reference/aggregation), for example
            `{"severity": {"terms": {"field": "severity"}}}`.
            """
    )
    @PluginProperty(dynamic = true, group = "processing")
    private Property<Map<String, Object>> aggregations;

    /** Renders every search property into an immutable {@link SearchQuery}. */
    protected SearchQuery searchQuery(RunContext runContext) throws IllegalVariableEvaluationException {
        return new SearchQuery(
            required(runContext, this.index, "index"),
            required(runContext, this.query, "query"),
            runContext.render(this.startTimestamp).as(Long.class).orElse(null),
            runContext.render(this.endTimestamp).as(Long.class).orElse(null),
            runContext.render(this.startOffset).as(Integer.class).orElse(null),
            runContext.render(this.maxHits).as(Integer.class).orElse(null),
            runContext.render(this.searchField).asList(String.class),
            runContext.render(this.snippetFields).asList(String.class),
            runContext.render(this.sortBy).asList(String.class),
            runContext.render(this.aggregations).asMap(String.class, Object.class)
        );
    }

    /**
     * Runs a search against an index.
     *
     * @param startTimestampOverride replaces the configured start timestamp when non-null
     */
    protected SearchResult search(RunContext runContext, HttpClient client, SearchQuery query, Long startTimestampOverride) throws Exception {
        var searchRequest = request(runContext, "POST", QuickwitService.pathSegment(query.index()) + "/search", null);

        return QuickwitService.search(client, searchRequest, query, startTimestampOverride);
    }
}