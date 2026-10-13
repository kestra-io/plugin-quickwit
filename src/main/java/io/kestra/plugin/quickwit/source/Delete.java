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
 * Detaches a source from a Quickwit index.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#delete-a-source">Delete a source</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete a Quickwit source",
    description = """
        Deletes a source from an index. Documents already indexed are kept; only the source and its checkpoint
        are removed.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Detach a retired Kafka topic from an index",
            full = true,
            code = """
                id: quickwit_delete_source
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.quickwit.source.Delete
                    url: "http://localhost:7280"
                    index: app-logs
                    source: legacy-source

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "Source {{ outputs.delete.source }} detached from {{ outputs.delete.index }}"
                """
        )
    }
)
public class Delete extends AbstractQuickwitSource implements RunnableTask<Delete.Output> {
    @Override
    public Delete.Output run(RunContext runContext) throws Exception {
        String rIndex = required(runContext, getIndex(), "index");
        String rSource = required(runContext, getSource(), "source");

        HttpRequest request = request(
            runContext,
            "DELETE",
            "indexes/" + pathSegment(rIndex) + "/sources/" + pathSegment(rSource),
            null
        ).build();

        try (var client = client(runContext)) {
            executeIgnoringBody(client, request, "deletion of source '" + rSource + "' on index '" + rIndex + "'");
        }

        runContext.logger().info("Deleted source '{}' from index '{}'", rSource, rIndex);

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
            description = "ID of the index the source was removed from."
        )
        private String index;

        @Schema(
            title = "Source ID",
            description = "ID of the source that was deleted."
        )
        private String source;
    }
}