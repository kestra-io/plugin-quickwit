package io.kestra.plugin.quickwit.deletetask;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.AbstractQuickwitTask;
import io.kestra.plugin.quickwit.models.DeleteTask;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Lists the delete tasks queued on a Quickwit index.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#list-delete-queries">List delete queries</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Quickwit delete tasks",
    description = "Returns every delete task queued on an index, in the order Quickwit stored them."
)
@Plugin(
    examples = {
        @Example(
            title = "Inspect the delete tasks of an index",
            full = true,
            code = """
                id: quickwit_list_delete_tasks
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.quickwit.deletetask.List
                    url: "http://localhost:7280"
                    index: app-logs

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.list.deleteTasks | size }} delete task(s) queued"
                """
        )
    }
)
public class List extends AbstractQuickwitTask implements RunnableTask<List.Output> {
    @Schema(
        title = "Index ID",
        description = "ID of the index to list the delete tasks of."
    )
    @PluginProperty(group = "main")
    private Property<String> index;

    @Override
    public List.Output run(RunContext runContext) throws Exception {
        String rIndex = required(runContext, this.index, "index");

        HttpRequest request = request(runContext, "GET", pathSegment(rIndex) + "/delete-tasks", null).build();

        java.util.List<DeleteTask> deleteTasks;
        try (var client = client(runContext)) {
            deleteTasks = executeList(client, request, DeleteTask.class, "listing of the delete tasks on index '" + rIndex + "'");
        }

        return Output.builder()
            .index(rIndex)
            .size(deleteTasks.size())
            .deleteTasks(deleteTasks)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Index ID",
            description = "ID of the index the delete tasks belong to."
        )
        private String index;

        @Schema(
            title = "Delete task count",
            description = "Number of delete tasks returned."
        )
        private Integer size;

        @Schema(
            title = "Delete tasks",
            description = "The delete tasks queued on the index."
        )
        private java.util.List<DeleteTask> deleteTasks;
    }
}