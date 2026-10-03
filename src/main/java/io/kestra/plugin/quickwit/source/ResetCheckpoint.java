package io.kestra.plugin.quickwit.source;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
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
 * Resets the checkpoint of a Quickwit source, so it re-reads its data from the beginning.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#reset-source-checkpoint">Reset source checkpoint</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Reset a Quickwit source checkpoint",
    description = """
        Resets the checkpoint of a source so it re-indexes its data from the beginning of the stream.

        Use it after a doc mapping change to replay already ingested data. Quickwit answers with an empty
        body, which is expected.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Replay a source after changing the doc mapping",
            full = true,
            code = """
                id: quickwit_reset_checkpoint
                namespace: company.team

                tasks:
                  - id: reset
                    type: io.kestra.plugin.quickwit.source.ResetCheckpoint
                    url: "http://localhost:7280"
                    index: app-logs
                    source: kafka-source

                  - id: check
                    type: io.kestra.plugin.quickwit.index.Get
                    url: "http://localhost:7280"
                    index: app-logs
                """
        )
    }
)
public class ResetCheckpoint extends AbstractQuickwitSource implements RunnableTask<ResetCheckpoint.Output> {
    @Override
    public ResetCheckpoint.Output run(RunContext runContext) throws Exception {
        String rIndex = required(runContext, getIndex(), "index");
        String rSource = required(runContext, getSource(), "source");

        HttpRequest request = request(
            runContext,
            "PUT",
            "indexes/" + pathSegment(rIndex) + "/sources/" + pathSegment(rSource) + "/reset-checkpoint",
            null
        ).build();

        try (var client = client(runContext)) {
            executeIgnoringBody(client, request, "checkpoint reset of source '" + rSource + "' on index '" + rIndex + "'");
        }

        runContext.logger().info("Reset the checkpoint of source '{}' on index '{}'", rSource, rIndex);

        return Output.builder()
            .index(rIndex)
            .source(rSource)
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
            description = "ID of the source whose checkpoint was reset."
        )
        private String source;
    }
}