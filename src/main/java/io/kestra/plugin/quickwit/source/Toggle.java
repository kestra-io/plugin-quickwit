package io.kestra.plugin.quickwit.source;

import java.util.Map;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Enables or disables a Quickwit source without deleting it.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#toggle-source">Toggle source</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Toggle a Quickwit source",
    description = """
        Enables or disables a source. A disabled source stops indexing without losing its checkpoint, so
        re-enabling it resumes where it left off. Quickwit answers with an empty body, which is expected.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Pause an ingestion pipeline during a maintenance window",
            full = true,
            code = """
                id: quickwit_toggle_source
                namespace: company.team

                tasks:
                  - id: pause
                    type: io.kestra.plugin.quickwit.source.Toggle
                    url: "http://localhost:7280"
                    index: app-logs
                    source: kafka-source
                    enable: false

                  - id: resume
                    type: io.kestra.plugin.quickwit.source.Toggle
                    url: "http://localhost:7280"
                    index: app-logs
                    source: kafka-source
                    enable: true
                """
        )
    }
)
public class Toggle extends AbstractQuickwitSource implements RunnableTask<Toggle.Output> {
    @Schema(
        title = "Enable",
        description = "`true` to let Quickwit index from this source, `false` to stop indexing without deleting its checkpoint."
    )
    @PluginProperty(group = "main")
    private Property<Boolean> enable;

    @Override
    public Toggle.Output run(RunContext runContext) throws Exception {
        String renderedIndex = required(runContext, getIndex(), "index");
        String renderedSource = required(runContext, getSource(), "source");
        boolean enableSource = runContext.render(this.enable).as(Boolean.class).orElseThrow(
            () -> new IllegalArgumentException("`enable` is required and must be `true` or `false`")
        );

        HttpRequest request = request(
            runContext,
            "PUT",
            "indexes/" + pathSegment(renderedIndex) + "/sources/" + pathSegment(renderedSource) + "/toggle",
            HttpRequest.JsonRequestBody.of(Map.of("enable", enableSource))
        ).build();

        try (var client = client(runContext)) {
            executeIgnoringBody(client, request, "toggling of source '" + renderedSource + "' on index '" + renderedIndex + "'");
        }

        runContext.logger().info("{} source '{}' on index '{}'", enableSource ? "Enabled" : "Disabled", renderedSource, renderedIndex);

        return Output.builder()
            .index(renderedIndex)
            .source(renderedSource)
            .enable(enableSource)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Index ID",
            description = "ID of the index the source belongs to."
        )
        private String index;

        @Schema(
            title = "Source ID",
            description = "ID of the source that was toggled."
        )
        private String source;

        @Schema(
            title = "Enabled",
            description = "`true` when the source was enabled, `false` when it was disabled."
        )
        private Boolean enable;
    }
}