package io.kestra.plugin.quickwit;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Connection settings shared by every Quickwit task.
 *
 * <p>The properties are declared flat, on the task itself, so a flow reads as
 * {@code url: ... / index: ...} without an extra nesting level. Everything here is optional except
 * {@link #url}.
 *
 * <p>Quickwit has no authentication layer of its own, so {@link #basicAuth} and {@link #headers} only
 * matter when the cluster is exposed through a reverse proxy or an API gateway.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQuickwitTask extends Task {
    @Schema(
        title = "Quickwit URL",
        description = """
            Base URL of the Quickwit REST API, including the scheme. The `api/v1` prefix is added automatically.
            A local node listens on port 7280, e.g. `http://localhost:7280`.
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> url;

    @Schema(
        title = "Basic authentication",
        description = """
            Optional HTTP basic authentication credentials.

            Quickwit itself has no authentication layer, so this is only needed when the cluster sits
            behind a reverse proxy or an API gateway that enforces basic auth.
            """
    )
    @Valid
    @ToString.Exclude
    @PluginProperty(group = "connection")
    private BasicAuth basicAuth;

    @Schema(
        title = "Custom HTTP headers",
        description = """
            Headers sent on every request, for example an `Authorization: Bearer ...` token or a gateway API key.

            Quickwit itself has no authentication layer, so this is only needed when the cluster sits
            behind an API gateway that injects credentials.
            """
    )
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "connection", secret = true)
    private Property<Map<String, String>> headers;

    @Schema(
        title = "Connect timeout",
        description = """
            Maximum time to wait to establish a connection to Quickwit, e.g. `PT10S`.
            When unset, the default of the underlying HTTP client applies.
            """
    )
    @PluginProperty(group = "execution")
    private Property<Duration> connectTimeout;

    @Schema(
        title = "Read timeout",
        description = """
            Maximum time to keep waiting for data on an idle connection, e.g. `PT5M`.
            A `commit=force` ingest or a large search may legitimately take a while, so raise it when needed.
            """
    )
    @PluginProperty(group = "execution")
    private Property<Duration> readTimeout;

    /** Creates the HTTP client configured from the connection properties of this task. */
    protected HttpClient client(RunContext runContext) throws IllegalVariableEvaluationException {
        return new HttpClient(runContext, QuickwitService.httpConfiguration(runContext, this.connectTimeout, this.readTimeout, this.basicAuth));
    }

    protected String renderedUrl(RunContext runContext) throws IllegalVariableEvaluationException {
        return QuickwitService.renderedUrl(runContext, this.url);
    }

    protected Map<String, String> renderedHeaders(RunContext runContext) throws IllegalVariableEvaluationException {
        return QuickwitService.renderedHeaders(runContext, this.headers);
    }

    /**
     * Builds a request against a Quickwit endpoint, with the task headers applied.
     *
     * @param path API path without the {@code api/v1} prefix, for example {@code indexes/hdfs-logs}
     */
    protected HttpRequest.HttpRequestBuilder request(RunContext runContext, String method, String path, Map<String, ?> query, HttpRequest.RequestBody body)
        throws IllegalVariableEvaluationException {
        return QuickwitService.request(method, QuickwitService.endpoint(renderedUrl(runContext), path, query), renderedHeaders(runContext), body);
    }

    protected HttpRequest.HttpRequestBuilder request(RunContext runContext, String method, String path, HttpRequest.RequestBody body)
        throws IllegalVariableEvaluationException {
        return request(runContext, method, path, Map.of(), body);
    }

    protected <T> T execute(HttpClient client, HttpRequest request, Class<T> type, String operation) throws Exception {
        return QuickwitService.execute(client, request, type, operation);
    }

    protected <T> List<T> executeList(HttpClient client, HttpRequest request, Class<T> type, String operation) throws Exception {
        return QuickwitService.executeList(client, request, type, operation);
    }

    protected void executeIgnoringBody(HttpClient client, HttpRequest request, String operation) throws Exception {
        QuickwitService.executeIgnoringBody(client, request, operation);
    }

    protected static String pathSegment(String value) {
        return QuickwitService.pathSegment(value);
    }

    /** Renders a required string property, failing with an actionable message when it is missing. */
    protected static String required(RunContext runContext, Property<String> property, String field) throws IllegalVariableEvaluationException {
        return QuickwitService.requireNonBlank(runContext.render(property).as(String.class).orElse(null), field);
    }

    /**
     * Renders a required object property, failing with an actionable message when it is missing or empty.
     *
     * @param documentation link to the Quickwit documentation describing the expected shape
     */
    protected static Map<String, Object> requiredMap(
        RunContext runContext,
        Property<Map<String, Object>> property,
        String field,
        String documentation
    ) throws IllegalVariableEvaluationException {
        Map<String, Object> rendered = runContext.render(property).asMap(String.class, Object.class);

        if (rendered.isEmpty()) {
            throw new IllegalArgumentException("`" + field + "` is required and cannot be empty, see " + documentation);
        }

        return rendered;
    }

    /**
     * Adds an optional value to a request body, omitting it when unset so Quickwit applies its own
     * default rather than rejecting an empty or null value.
     */
    protected static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null && !(value instanceof Map<?, ?> map && map.isEmpty())) {
            body.put(key, value);
        }
    }

    /**
     * Optional basic authentication, for clusters exposed through a reverse proxy or a gateway.
     */
    @SuperBuilder
    @ToString
    @EqualsAndHashCode
    @Getter
    @NoArgsConstructor
    public static class BasicAuth {
        @Schema(
            title = "Username",
            description = "Username for HTTP basic authentication."
        )
        @ToString.Exclude
        @PluginProperty(group = "connection")
        private Property<String> username;

        @Schema(
            title = "Password",
            description = "Password for HTTP basic authentication."
        )
        @ToString.Exclude
        @EqualsAndHashCode.Exclude
        @PluginProperty(group = "connection", secret = true)
        private Property<String> password;
    }
}