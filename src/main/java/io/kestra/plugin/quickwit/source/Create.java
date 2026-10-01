package io.kestra.plugin.quickwit.source;

import java.util.LinkedHashMap;
import java.util.Map;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.models.Source;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Attaches a source to a Quickwit index.
 *
 * @see <a href="https://quickwit.io/docs/configuration/source-config">Quickwit source configuration</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a Quickwit source",
    description = """
        Attaches a streaming source, for example Kafka, Kinesis or Pulsar, to an index so Quickwit indexes
        it continuously.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Index a Kafka topic into an existing index",
            full = true,
            code = """
                id: quickwit_create_source
                namespace: company.team

                tasks:
                  - id: create
                    type: io.kestra.plugin.quickwit.source.Create
                    url: "http://localhost:7280"
                    index: app-logs
                    source: kafka-source
                    configVersion: "0.8"
                    sourceType: kafka
                    numPipelines: 2
                    params:
                      topic: app-logs
                      client_params:
                        bootstrap.servers: "kafka:9092"

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "Source {{ outputs.create.source.sourceId }} attached"
                """
        )
    }
)
public class Create extends AbstractQuickwitSource implements RunnableTask<Create.Output> {
    @Schema(
        title = "Configuration format version",
        description = """
            Version of the source configuration format, which must match the version of your Quickwit
            cluster, for example `0.8`.

            Named `configVersion` and not `version` because `version` is reserved by Kestra to pin a
            plugin version.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> configVersion;

    @Schema(
        title = "Source type",
        description = "Type of the source: `kafka`, `kinesis` or `pulsar`. It cannot be changed afterwards."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> sourceType;

    @Schema(
        title = "Source parameters",
        description = """
            Parameters of the source, whose shape depends on the source type. See the
            [source configuration documentation](https://quickwit.io/docs/configuration/source-config).
            """
    )
    @NotNull
    @PluginProperty(dynamic = true, group = "main")
    private Property<Map<String, Object>> params;

    @Schema(
        title = "Number of pipelines",
        description = "Number of indexing pipelines running per node for this source. Quickwit defaults to `1`."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> numPipelines;

    @Schema(
        title = "Transform",
        description = """
            A [VRL](https://vector.dev/docs/reference/vrl/) transformation applied to incoming documents,
            for example `.message = downcase!(.message)`.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> transform;

    @Override
    public Create.Output run(RunContext runContext) throws Exception {
        String renderedIndex = required(runContext, getIndex(), "index");
        String renderedSource = required(runContext, getSource(), "source");
        Map<String, Object> params = requiredMap(
            runContext,
            this.params,
            "params",
            "https://quickwit.io/docs/configuration/source-config"
        );

        Map<String, Object> configuration = new LinkedHashMap<>();
        putIfPresent(configuration, "version", runContext.render(this.configVersion).as(String.class).orElse(null));
        configuration.put("source_id", renderedSource);
        configuration.put("source_type", required(runContext, this.sourceType, "sourceType"));
        configuration.put("params", params);
        putIfPresent(configuration, "num_pipelines", runContext.render(this.numPipelines).as(Integer.class).orElse(null));
        putIfPresent(configuration, "transform", runContext.render(this.transform).as(String.class).orElse(null));

        HttpRequest request = request(
            runContext,
            "POST",
            "indexes/" + pathSegment(renderedIndex) + "/sources",
            HttpRequest.JsonRequestBody.of(configuration)
        ).build();

        Source source;
        try (var client = client(runContext)) {
            source = execute(client, request, Source.class, "creation of source '" + renderedSource + "' on index '" + renderedIndex + "'");
        }

        return Output.builder()
            .index(renderedIndex)
            .source(source)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Index ID",
            description = "ID of the index the source was attached to."
        )
        private String index;

        @Schema(
            title = "Source",
            description = "The source configuration as Quickwit created it."
        )
        private Source source;
    }
}