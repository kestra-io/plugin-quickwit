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
 * Index management tasks, sharing the index ID and the format version of an index configuration.
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

    @Schema(
        title = "Configuration format version",
        description = """
            Version of the index configuration format, which must match the version of your Quickwit
            cluster, for example `0.8`.

            Named `configVersion` and not `version` because `version` is reserved by Kestra to pin a
            plugin version.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> configVersion;
}