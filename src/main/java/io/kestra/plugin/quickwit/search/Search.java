package io.kestra.plugin.quickwit.search;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.quickwit.models.SearchResult;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

/**
 * Searches documents in a Quickwit index.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Search Quickwit indexes",
    description = """
        Runs a Quickwit search query against an index and returns the matching documents.

        Supports returning every hit, only the first one, or storing all hits to Kestra internal
        storage. Only the current result page is returned, so set `maxHits` (and paginate) when you
        need more.
        """
)
@Plugin(
    metrics = {
        @Metric(name = "requests.count", type = Counter.TYPE, description = "Number of search requests sent"),
        @Metric(name = "records", type = Counter.TYPE, unit = "records", description = "Number of documents returned"),
        @Metric(name = "requests.duration", type = Timer.TYPE, description = "Time taken by Quickwit to process the query")
    },
    examples = {
        @Example(
            title = "Search the errors logged in the last hour",
            full = true,
            code = """
                id: quickwit_search
                namespace: company.team

                tasks:
                  - id: search
                    type: io.kestra.plugin.quickwit.search.Search
                    url: "https://quickwit.example.com:7280"
                    index: app-logs
                    query: "severity:ERROR"
                    startTimestamp: "{{ now() | dateAdd(-1, 'HOURS') | date('X') }}"
                    maxHits: 100
                    fetchType: FETCH

                  - id: log_count
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ outputs.search.size }} errors"
                """
        ),
        @Example(
            title = "Count the errors of each service over the last day, behind a protected gateway",
            full = true,
            code = """
                id: quickwit_search_aggregations
                namespace: company.team

                tasks:
                  - id: search
                    type: io.kestra.plugin.quickwit.search.Search
                    url: "https://quickwit.example.com:7280"
                    headers:
                      Authorization: "Bearer {{ secret('QUICKWIT_GATEWAY_TOKEN') }}"
                    index: app-logs
                    query: "severity:ERROR"
                    startTimestamp: "{{ now() | dateAdd(-1, 'DAYS') | date('X') }}"
                    aggregations:
                      service:
                        terms:
                          field: service

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "Top services: {{ outputs.search.aggregations | json }}"
                """
        )
    }
)
public class Search extends AbstractQuickwitSearch implements RunnableTask<Search.Output> {
    @Schema(
        title = "Result handling mode",
        description = """
            Controls how hits are exposed in the outputs.

            `FETCH` (default) returns every hit of the current page, `FETCH_ONE` only the first hit,
            `STORE` writes all hits to Kestra internal storage and returns a URI, and `NONE` leaves
            the row outputs empty.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Search.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        SearchResult result;
        try (var client = client(runContext)) {
            result = search(runContext, client, searchQuery(runContext), null);
        }

        if (result.getErrors() != null && !result.getErrors().isEmpty()) {
            logger.warn("Quickwit reported non-fatal search errors: {}", result.getErrors());
        }

        if (result.getElapsedTimeMicros() != null) {
            runContext.metric(Timer.of("requests.duration", Duration.ofNanos(result.getElapsedTimeMicros() * 1_000L)));
        }
        runContext.metric(Counter.of("requests.count", 1));

        List<Map<String, Object>> hits = result.getHits() != null ? result.getHits() : List.of();
        runContext.metric(Counter.of("records", hits.size()));

        FetchType fetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        return Output.builder()
            .fetchType(fetchType)
            .total(result.getNumHits() != null ? result.getNumHits() : (long) hits.size())
            .size(fetchType == FetchType.NONE ? 0 : hits.size())
            .rows(fetchType == FetchType.FETCH ? hits : null)
            .row(fetchType == FetchType.FETCH_ONE ? hits.isEmpty() ? null : hits.getFirst() : null)
            .uri(fetchType == FetchType.STORE ? store(runContext, hits) : null)
            .elapsedTimeMicros(result.getElapsedTimeMicros())
            .snippets(result.getSnippets())
            .aggregations(result.getAggregations())
            .errors(result.getErrors())
            .build();
    }

    /**
     * Writes the hits to Kestra internal storage as an ION file.
     */
    private URI store(RunContext runContext, List<Map<String, Object>> hits) throws IOException {
        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();

        try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            FileSerde.writeAll(output, Flux.fromIterable(hits)).block();
        }

        return runContext.storage().putFile(tempFile);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Returned document count",
            description = "Number of documents included in the outputs for the selected fetch type."
        )
        private Integer size;

        @Schema(
            title = "Total matching documents",
            description = "Total number of documents matching the query, regardless of pagination."
        )
        private Long total;

        @Schema(
            title = "Fetched documents",
            description = "Available only when `fetchType=FETCH`; the documents of the current result page."
        )
        private List<Map<String, Object>> rows;

        @Schema(
            title = "First document",
            description = "Available only when `fetchType=FETCH_ONE`; the first matching document."
        )
        private Map<String, Object> row;

        @Schema(
            title = "Stored documents URI",
            description = "Available only when `fetchType=STORE`; path of the ION file holding the documents."
        )
        private URI uri;

        @Schema(
            title = "Processing time",
            description = "Time taken by Quickwit to process the query, in microseconds."
        )
        private Long elapsedTimeMicros;

        @Schema(
            title = "Snippets",
            description = "Extracted snippets, present only when snippet fields were requested."
        )
        private List<Map<String, Object>> snippets;

        @Schema(
            title = "Aggregations",
            description = "Aggregation results, present only when aggregations were requested."
        )
        private Map<String, Object> aggregations;

        @Schema(
            title = "Search errors",
            description = "Non-fatal errors reported by Quickwit while executing the query."
        )
        private List<String> errors;

        @Schema(
            title = "Result handling mode",
            description = "The fetch type that was applied to produce these outputs."
        )
        private FetchType fetchType;
    }
}