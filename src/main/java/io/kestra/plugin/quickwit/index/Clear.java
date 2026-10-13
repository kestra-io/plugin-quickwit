package io.kestra.plugin.quickwit.index;

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
 * Drops every document of a Quickwit index while keeping its definition.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#clears-an-index">Clear an index</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Clear a Quickwit index",
    description = """
        Deletes every split of an index and resets the checkpoint of its sources, while keeping the index
        definition in place. Quickwit answers with an empty body, which is expected.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Reset an index before replaying a day of logs",
            full = true,
            code = """
                id: quickwit_clear_index
                namespace: company.team

                tasks:
                  - id: clear
                    type: io.kestra.plugin.quickwit.index.Clear
                    url: "http://localhost:7280"
                    index: app-logs

                  - id: reingest
                    type: io.kestra.plugin.quickwit.ingest.Ingest
                    url: "http://localhost:7280"
                    index: app-logs
                    from: "{{ outputs.replay_file }}"
                    commit: FORCE
                """
        )
    }
)
public class Clear extends AbstractQuickwitIndex implements RunnableTask<Clear.Output> {
    @Override
    public Clear.Output run(RunContext runContext) throws Exception {
        String rIndex = required(runContext, getIndex(), "index");

        // Quickwit documents this endpoint as returning an empty body.
        HttpRequest request = request(runContext, "PUT", "indexes/" + pathSegment(rIndex) + "/clear", null).build();

        try (var client = client(runContext)) {
            executeIgnoringBody(client, request, "clearing of index '" + rIndex + "'");
        }

        runContext.logger().info("Cleared index '{}': all splits deleted and source checkpoints reset", rIndex);

        return Output.builder()
            .index(rIndex)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Index ID",
            description = "ID of the index that was cleared."
        )
        private String index;
    }
}