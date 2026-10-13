package io.kestra.plugin.quickwit.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

/**
 * A Quickwit delete task, which removes every document matching a query from an index.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#create-a-delete-task">Quickwit delete API</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Builder
@Jacksonized
@AllArgsConstructor
@NoArgsConstructor
@Schema(
    title = "Delete task",
    description = "A delete task queued on an index, deleting every document matching its query."
)
public class DeleteTask {
    @Schema(
        title = "Creation timestamp",
        description = "Creation timestamp of the delete task, in seconds."
    )
    @JsonProperty("create_timestamp")
    private Long createTimestamp;

    @Schema(
        title = "Operation stamp",
        description = "Unique operation stamp associated with the delete task."
    )
    private Long opstamp;

    @Schema(
        title = "Delete query",
        description = "The query selecting the documents to delete."
    )
    @JsonProperty("delete_query")
    private DeleteQuery deleteQuery;

    @Schema(
        title = "Delete query",
        description = "Query selecting the documents to delete, restricted to a time range and a set of fields."
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @Builder
    @Jacksonized
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DeleteQuery {
        @Schema(
            title = "Query",
            description = "Query text, using the Quickwit query language."
        )
        private String query;

        @Schema(
            title = "Search fields",
            description = "Fields to search on when the query does not qualify a field name."
        )
        @JsonProperty("search_field")
        private List<String> searchField;

        @Schema(
            title = "Start timestamp",
            description = "Restricts the deletion to documents with `timestamp >= start_timestamp`, in seconds."
        )
        @JsonProperty("start_timestamp")
        private Long startTimestamp;

        @Schema(
            title = "End timestamp",
            description = "Restricts the deletion to documents with `timestamp < end_timestamp`, in seconds."
        )
        @JsonProperty("end_timestamp")
        private Long endTimestamp;
    }
}