package io.kestra.plugin.quickwit.source;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.quickwit.AbstractQuickwitTask;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Source management tasks, sharing the index ID and source ID.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#create-a-source">Quickwit source API</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQuickwitSource extends AbstractQuickwitTask {
    @Schema(
        title = "Index ID",
        description = "ID of the index the source belongs to."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> index;

    @Schema(
        title = "Source ID",
        description = """
            ID of the source, unique within its index. See the
            [source ID validation rules](https://quickwit.io/docs/configuration/source-config).
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> source;
}