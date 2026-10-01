package io.kestra.plugin.quickwit.index;

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
 * Index management tasks, sharing the index ID.
 *
 * @see <a href="https://quickwit.io/docs/reference/rest-api#index-api">Quickwit index API</a>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQuickwitIndex extends AbstractQuickwitTask {
    @Schema(
        title = "Index ID",
        description = """
            ID of the index. See the
            [index ID validation rules](https://quickwit.io/docs/configuration/index-config#index-id).
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> index;
}