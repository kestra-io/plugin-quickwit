package io.kestra.plugin.quickwit.deletetask;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.models.DeleteTask;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Queues the deletion of every document of an index matching a query.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#create-a-delete-task">Create a delete task</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a Quickwit delete task",
    description = """
        Queues a delete task on an index: every document matching the query is deleted by the janitor of the
        cluster.

        The task only appends the delete task to the metastore queue, so this call returns immediately and the
        documents are removed asynchronously.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Apply a retention policy to yesterday's logs",
            full = true,
            code = """
                id: quickwit_delete_task
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.quickwit.deletetask.Create
                    url: "http://localhost:7280"
                    index: app-logs
                    query: "*"
                    startTimestamp: "{{ now() | dateAdd(-30, 'DAYS') | date('X') }}"
                    endTimestamp: "{{ now() | dateAdd(-29, 'DAYS') | date('X') }}"

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "Queued delete task {{ outputs.delete.opstamp }}"
                """
        )
    }
)
public class Create extends AbstractQuickwitDeleteTask implements RunnableTask<Create.Output> {
    @Override
    public Create.Output run(RunContext runContext) throws Exception {
        String renderedIndex = required(runContext, getIndex(), "index");

        HttpRequest request = request(
            runContext,
            "POST",
            pathSegment(renderedIndex) + "/delete-tasks",
            HttpRequest.JsonRequestBody.of(deleteQuery(runContext))
        ).build();

        DeleteTask deleteTask;
        try (var client = client(runContext)) {
            deleteTask = execute(client, request, DeleteTask.class, "creation of a delete task on index '" + renderedIndex + "'");
        }

        return Output.builder()
            .index(renderedIndex)
            .createTimestamp(deleteTask.getCreateTimestamp())
            .opstamp(deleteTask.getOpstamp())
            .deleteTask(deleteTask)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Index ID",
            description = "ID of the index the delete task was queued on."
        )
        private String index;

        @Schema(
            title = "Creation timestamp",
            description = "Creation timestamp of the delete task, in seconds."
        )
        private Long createTimestamp;

        @Schema(
            title = "Operation stamp",
            description = "Unique operation stamp of the delete task."
        )
        private Long opstamp;

        @Schema(
            title = "Delete task",
            description = "The queued delete task, as Quickwit stored it."
        )
        private DeleteTask deleteTask;
    }
}