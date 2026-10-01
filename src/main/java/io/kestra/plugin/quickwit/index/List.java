package io.kestra.plugin.quickwit.index;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickwit.AbstractQuickwitTask;
import io.kestra.plugin.quickwit.models.IndexMetadata;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Lists every Quickwit index of the cluster.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#get-all-indexes-metadata">Get all indexes metadata</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Quickwit indexes",
    description = "Returns the metadata of every index present in the metastore."
)
@Plugin(
    examples = {
        @Example(
            title = "List the indexes of a cluster",
            full = true,
            code = """
                id: quickwit_list_indexes
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.quickwit.index.List
                    url: "http://localhost:7280"

                  - id: report
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ outputs.list.indexes | map(indexConfig.index_id) | join(', ') }}"
                """
        )
    }
)
public class List extends AbstractQuickwitTask implements RunnableTask<List.Output> {
    @Override
    public List.Output run(RunContext runContext) throws Exception {
        HttpRequest request = request(runContext, "GET", "indexes", null).build();

        java.util.List<IndexMetadata> indexes;
        try (var client = client(runContext)) {
            indexes = executeList(client, request, IndexMetadata.class, "listing of the indexes");
        }

        return Output.builder()
            .size(indexes.size())
            .indexes(indexes)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Index count",
            description = "Number of indexes returned."
        )
        private Integer size;

        @Schema(
            title = "Indexes",
            description = "Metadata of every index present in the metastore."
        )
        private java.util.List<IndexMetadata> indexes;
    }
}