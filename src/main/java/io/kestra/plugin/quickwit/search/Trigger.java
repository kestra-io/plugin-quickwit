package io.kestra.plugin.quickwit.search;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.quickwit.AbstractQuickwitTask;
import io.kestra.plugin.quickwit.QuickwitService;
import io.kestra.plugin.quickwit.models.SearchQuery;
import io.kestra.plugin.quickwit.models.SearchResult;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Fires a flow when new documents matching a query show up in a Quickwit index.
 *
 * <p>The trigger polls Quickwit on every {@code interval}. It remembers, in the namespace KV Store, the
 * timestamp up to which documents have already been delivered, and each poll searches
 * {@code timestamp >= watermark} before advancing it. A document is therefore never delivered twice,
 * while documents indexed late are still picked up on a later poll.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on new Quickwit documents",
    description = """
        Polls a Quickwit index on a fixed interval and fires an execution as soon as documents matching
        the query appear.

        The last delivered timestamp is kept in the namespace KV Store and advanced on every successful
        poll, so a given document is only ever delivered once.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Alert when new errors are logged",
            full = true,
            code = """
                id: quickwit_error_watch
                namespace: company.team

                triggers:
                  - id: on_errors
                    type: io.kestra.plugin.quickwit.search.Trigger
                    url: "https://quickwit.example.com:7280"
                    index: app-logs
                    query: "severity:ERROR"
                    interval: PT5M

                tasks:
                  - id: notify
                    type: io.kestra.plugin.core.log.Log
                    message: "New errors: {{ trigger.documents | length }}"
                """
        ),
        @Example(
            title = "React to new errors on a Quickwit cluster protected by a gateway",
            full = true,
            code = """
                id: quickwit_error_watch_private
                namespace: company.team

                triggers:
                  - id: on_errors
                    type: io.kestra.plugin.quickwit.search.Trigger
                    url: "https://quickwit.example.com:7280"
                    headers:
                      Authorization: "Bearer {{ secret('QUICKWIT_GATEWAY_TOKEN') }}"
                    index: app-logs
                    query: "severity:ERROR AND service:checkout"
                    interval: PT5M
                    maxHits: 500

                tasks:
                  - id: notify
                    type: io.kestra.plugin.core.log.Log
                    message: "New checkout errors: {{ trigger.documents | length }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {
    private static final String WATERMARK_DESCRIPTION = "Last timestamp delivered by the Quickwit search trigger";

    @Builder.Default
    private Duration interval = Duration.ofSeconds(60);

    @Schema(
        title = "Quickwit URL",
        description = """
            Base URL of the Quickwit REST API, including the scheme. The `api/v1` prefix is added automatically.
            A local node listens on port 7280, e.g. `http://localhost:7280`.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> url;

    @Schema(
        title = "Basic authentication",
        description = """
            Optional HTTP basic authentication credentials.

            Quickwit itself has no authentication layer, so this is only needed when the cluster sits
            behind a reverse proxy or an API gateway that enforces basic auth.
            """
    )
    @Valid
    @ToString.Exclude
    @PluginProperty(group = "connection")
    private AbstractQuickwitTask.BasicAuth basicAuth;

    @Schema(
        title = "Custom HTTP headers",
        description = """
            Headers sent on every poll, for example an `Authorization: Bearer ...` token or a gateway API key.

            Quickwit itself has no authentication layer, so this is only needed when the cluster sits
            behind an API gateway that injects credentials.
            """
    )
    @ToString.Exclude
    @PluginProperty(group = "connection")
    private Property<Map<String, String>> headers;

    @Schema(
        title = "Connect timeout",
        description = """
            Maximum time to wait to establish a connection to Quickwit, e.g. `PT10S`.
            When unset, the default of the underlying HTTP client applies.
            """
    )
    @PluginProperty(group = "execution")
    private Property<Duration> connectTimeout;

    @Schema(
        title = "Read timeout",
        description = """
            Maximum time to keep waiting for data on an idle connection, e.g. `PT5M`.
            Raise it when the index is large enough for a poll to take a while.
            """
    )
    @PluginProperty(group = "execution")
    private Property<Duration> readTimeout;

    @Schema(
        title = "Index ID",
        description = "ID of the index to watch."
    )
    @PluginProperty(group = "main")
    private Property<String> index;

    @Schema(
        title = "Query",
        description = """
            Query selecting the documents to react to, using the
            [Quickwit query language](https://quickwit.io/docs/reference/query-language), for example `severity:ERROR`.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> query;

    @Schema(
        title = "Maximum number of hits",
        description = """
            Maximum number of documents to fetch per poll. Quickwit defaults to `20`.

            Raise it when documents are ingested in bursts. The watermark only advances after a
            successful poll, so documents beyond this limit are delivered on a later poll instead of
            being skipped.
            """
    )
    @PluginProperty(group = "processing")
    private Property<Integer> maxHits;

    @Schema(
        title = "State key",
        description = """
            KV Store key holding the watermark of this trigger.
            Defaults to `<namespace>_<flowId>_<triggerId>`; change it only to deliberately share a
            watermark between triggers.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> stateKey;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        SearchQuery query = searchQuery(runContext);
        String key = stateKey(runContext, context);

        // absent watermark => first poll, which deliberately returns the whole current result set
        Optional<Long> watermark = readWatermark(runContext, key);

        SearchResult result;
        var configuration = QuickwitService.httpConfiguration(runContext, this.connectTimeout, this.readTimeout, this.basicAuth);

        try (var client = new HttpClient(runContext, configuration)) {
            result = QuickwitService.search(runContext, client, this.url, this.headers, query, watermark.orElse(null));
        }

        List<Map<String, Object>> documents = result.getHits() != null ? result.getHits() : List.of();
        if (documents.isEmpty()) {
            logger.debug("No new document matching the query on index '{}'", query.index());
            return Optional.empty();
        }

        long advanced = Instant.now().getEpochSecond();
        writeWatermark(runContext, key, advanced);

        logger.info("Triggering on {} new document(s) on index '{}'", documents.size(), query.index());

        Output output = Output.builder()
            .index(query.index())
            .query(query.query())
            .documents(documents)
            .numHits(result.getNumHits() != null ? result.getNumHits() : (long) documents.size())
            .elapsedTimeMicros(result.getElapsedTimeMicros())
            .watermark(advanced)
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    private SearchQuery searchQuery(RunContext runContext) throws IllegalVariableEvaluationException {
        return SearchQuery.of(
            QuickwitService.requireNonBlank(runContext.render(this.index).as(String.class).orElse(null), "index"),
            QuickwitService.requireNonBlank(runContext.render(this.query).as(String.class).orElse(null), "query"),
            runContext.render(this.maxHits).as(Integer.class).orElse(null)
        );
    }

    private String stateKey(RunContext runContext, TriggerContext context) throws IllegalVariableEvaluationException {
        return runContext.render(this.stateKey)
            .as(String.class)
            .orElseGet(() -> defaultKey(context.getNamespace(), context.getFlowId(), this.id));
    }

    /**
     * Reads the last delivered timestamp.
     *
     * <p>A missing or unreadable watermark simply means the next poll starts from the beginning, which
     * is safer than skipping documents.
     */
    private Optional<Long> readWatermark(RunContext runContext, String key) {
        try {
            return runContext.namespaceKv(runContext.flowInfo().namespace())
                .getValue(key)
                .map(value -> Long.parseLong(new String((byte[]) value.value(), StandardCharsets.UTF_8).trim()));
        } catch (Exception e) {
            runContext.logger().warn("Unable to read the Quickwit search trigger watermark '{}', searching the whole window: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeWatermark(RunContext runContext, String key, long watermark) throws Exception {
        runContext.namespaceKv(runContext.flowInfo().namespace()).put(
            key,
            new KVValueAndMetadata(
                new KVMetadata(WATERMARK_DESCRIPTION, (Duration) null),
                Long.toString(watermark).getBytes(StandardCharsets.UTF_8)
            )
        );
    }

    /** Default watermark key, aligned with the convention used by the other stateful Kestra triggers. */
    private static String defaultKey(String namespace, String flowId, String triggerId) {
        return String.join("_", namespace, flowId, triggerId);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Watched index",
            description = "ID of the index that was polled."
        )
        private String index;

        @Schema(
            title = "Query",
            description = "Query that selected the documents."
        )
        private String query;

        @Schema(
            title = "New documents",
            description = "Documents matching the query that no previous poll had delivered."
        )
        private List<Map<String, Object>> documents;

        @Schema(
            title = "Total matching documents",
            description = "Total number of documents matching the query over the searched window."
        )
        private Long numHits;

        @Schema(
            title = "Processing time",
            description = "Time taken by Quickwit to process the query, in microseconds."
        )
        private Long elapsedTimeMicros;

        @Schema(
            title = "Watermark",
            description = "Timestamp up to which documents have now been delivered, in seconds."
        )
        private Long watermark;
    }
}