package io.kestra.plugin.quickwit.deletetask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import jakarta.validation.constraints.NotNull;
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
                    startTimestamp: "{{ now() | dateAdd(-30, 'DAYS') | timestamp }}"
                    endTimestamp: "{{ now() | dateAdd(-29, 'DAYS') | timestamp }}"

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "Queued delete task {{ outputs.delete.opstamp }}"
                """
        )
    }
)
public class Create extends AbstractQuickwitTask implements RunnableTask<Create.Output> {
    @Schema(
        title = "Index ID",
        description = "ID of the index to delete documents from."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> index;

    @Schema(
        title = "Query",
        description = """
            Query selecting the documents to delete, using the
            [Quickwit query language](https://quickwit.io/docs/reference/query-language), for example `message:trash`.

            The deletion is applied asynchronously by the janitor of the cluster.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> query;

    @Schema(
        title = "Start timestamp",
        description = "Restricts the deletion to documents with `timestamp >= startTimestamp`, in seconds."
    )
    @PluginProperty(group = "main")
    private Property<Long> startTimestamp;

    @Schema(
        title = "End timestamp",
        description = "Restricts the deletion to documents with `timestamp < endTimestamp`, in seconds."
    )
    @PluginProperty(group = "main")
    private Property<Long> endTimestamp;

    @Schema(
        title = "Search fields",
        description = "Fields to search on when the query does not qualify a field name."
    )
    @PluginProperty(group = "processing")
    private Property<List<String>> searchField;

    @Override
    public Create.Output run(RunContext runContext) throws Exception {
        String rIndex = required(runContext, this.index, "index");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", required(runContext, this.query, "query"));
        putIfPresent(body, "start_timestamp", runContext.render(this.startTimestamp).as(Long.class).orElse(null));
        putIfPresent(body, "end_timestamp", runContext.render(this.endTimestamp).as(Long.class).orElse(null));

        List<String> searchField = runContext.render(this.searchField).asList(String.class);
        if (!searchField.isEmpty()) {
            // plural here, unlike the singular `search_field` of the search API, and an array rather
            // than the comma-separated string that endpoint takes
            body.put("search_fields", searchField);
        }

        HttpRequest request = request(
            runContext,
            "POST",
            pathSegment(rIndex) + "/delete-tasks",
            HttpRequest.JsonRequestBody.of(body)
        ).build();

        DeleteTask deleteTask;
        try (var client = client(runContext)) {
            deleteTask = execute(client, request, DeleteTask.class, "creation of a delete task on index '" + rIndex + "'");
        }

        return Output.builder()
            .index(rIndex)
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