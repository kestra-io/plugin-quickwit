package io.kestra.plugin.quickwit.index;

import java.util.List;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.models.DeletedSplit;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Deletes a Quickwit index and every split file it owns.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#delete-an-index">Delete an index</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete a Quickwit index",
    description = """
        Deletes an index: its splits are removed from the metastore and from the storage.

        This cannot be undone. Use
        [Clear](https://kestra.io/docs/plugins/plugin-quickwit/io.kestra.plugin.quickwit.index.Clear) to keep the
        index definition and only drop its data.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Delete an index once its retention window has passed",
            full = true,
            code = """
                id: quickwit_delete_index
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.quickwit.index.Delete
                    url: "http://localhost:7280"
                    index: app-logs

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "Removed {{ outputs.delete.size }} split(s)"
                """
        )
    }
)
public class Delete extends AbstractQuickwitIndex implements RunnableTask<Delete.Output> {
    @Override
    public Delete.Output run(RunContext runContext) throws Exception {
        String rIndex = required(runContext, getIndex(), "index");

        HttpRequest request = request(runContext, "DELETE", "indexes/" + pathSegment(rIndex), null).build();

        List<DeletedSplit> deletedSplits;
        try (var client = client(runContext)) {
            deletedSplits = executeList(client, request, DeletedSplit.class, "deletion of index '" + rIndex + "'");
        }

        runContext.logger().info("Deleted index '{}' and {} split file(s)", rIndex, deletedSplits.size());

        return Output.builder()
            .index(rIndex)
            .size(deletedSplits.size())
            .deletedSplits(deletedSplits)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Index ID",
            description = "ID of the index that was deleted."
        )
        private String index;

        @Schema(
            title = "Deleted split count",
            description = "Number of split files removed along with the index."
        )
        private Integer size;

        @Schema(
            title = "Deleted splits",
            description = "The split files that were removed from the storage."
        )
        private List<DeletedSplit> deletedSplits;
    }
}