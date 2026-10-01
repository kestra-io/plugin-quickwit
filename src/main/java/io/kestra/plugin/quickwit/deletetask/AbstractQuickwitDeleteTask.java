package io.kestra.plugin.quickwit.deletetask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.AbstractQuickwitTask;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Delete task tasks, sharing the index ID and the query selecting the documents to delete.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#create-a-delete-task">Quickwit delete API</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQuickwitDeleteTask extends AbstractQuickwitTask {
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

    /** Builds the JSON body of the delete query. */
    protected Map<String, Object> deleteQuery(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", required(runContext, this.query, "query"));

        Long startTimestamp = runContext.render(this.startTimestamp).as(Long.class).orElse(null);
        if (startTimestamp != null) {
            body.put("start_timestamp", startTimestamp);
        }

        Long endTimestamp = runContext.render(this.endTimestamp).as(Long.class).orElse(null);
        if (endTimestamp != null) {
            body.put("end_timestamp", endTimestamp);
        }

        List<String> searchField = runContext.render(this.searchField).asList(String.class);
        if (!searchField.isEmpty()) {
            body.put("search_field", searchField);
        }

        return body;
    }
}