package io.kestra.plugin.quickwit;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BasicAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.quickwit.AbstractQuickwitTask.BasicAuth;
import io.kestra.plugin.quickwit.models.SearchQuery;
import io.kestra.plugin.quickwit.models.SearchResult;

/**
 * Thin helper around the Quickwit REST API, shared by every task and trigger of the plugin.
 *
 * <p>Quickwit exposes a plain REST API under {@code /api/v1}, has no Java client library and, as of
 * its 0.9 release, ships no authentication of its own: the REST server layers CORS, compression and
 * tracing only, and its OpenAPI document declares no security scheme. Requests therefore go through
 * Kestra's {@link HttpClient}, with optional basic auth or custom headers for deployments sitting
 * behind a reverse proxy or an API gateway.
 *
 * <p>Everything is static: a task extends {@link io.kestra.core.models.tasks.Task} and a trigger
 * extends {@link io.kestra.core.models.triggers.AbstractTrigger}, so neither can inherit behaviour
 * from a common superclass.
 */
public final class QuickwitService {
    /**
     * Prefix shared by every Quickwit endpoint.
     *
     * @see <a href="https://quickwit.io/docs/reference/rest-api#api-version">API version</a>
     */
    public static final String API_PREFIX = "api/v1";

    /** Default TCP port of a Quickwit node. */
    public static final int DEFAULT_PORT = 7280;

    private static final String BODY_MARKER = "and body:";

    private QuickwitService() {
    }

    /**
     * Builds the HTTP client configuration from the connection properties of a task or trigger.
     *
     * @param basicAuth credentials for deployments fronted by a proxy, {@code null} for a bare Quickwit node
     */
    public static HttpConfiguration httpConfiguration(
        RunContext runContext,
        Property<Duration> connectTimeout,
        Property<Duration> readTimeout,
        BasicAuth basicAuth
    ) throws IllegalVariableEvaluationException {
        var timeout = TimeoutConfiguration.builder();

        if (readTimeout != null) {
            timeout.readIdleTimeout(readTimeout);
        }

        // Only set connectTimeout when the user provided one: a zero duration is an immediate
        // timeout rather than "unbounded", so defaulting it here would break every request.
        if (connectTimeout != null) {
            timeout.connectTimeout(connectTimeout);
        }

        var builder = HttpConfiguration.builder()
            .timeout(timeout.build());

        if (basicAuth != null) {
            builder.auth(
                BasicAuthConfiguration.builder()
                    .username(basicAuth.getUsername())
                    .password(basicAuth.getPassword())
                    .build()
            );
        }

        return builder.build();
    }

    /**
     * Renders the configured URL, failing early with an actionable message rather than letting the
     * HTTP client choke on a malformed URI.
     */
    public static String renderedUrl(RunContext runContext, Property<String> url) throws IllegalVariableEvaluationException {
        String rendered = runContext.render(url).as(String.class).orElse(null);

        if (StringUtils.isBlank(rendered)) {
            throw new IllegalArgumentException("`url` is required and cannot be blank, e.g. `http://localhost:" + DEFAULT_PORT + "`");
        }

        String trimmed = rendered.trim();

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw invalidUrl(trimmed, e);
        }

        if (uri.getScheme() == null || uri.getHost() == null) {
            throw invalidUrl(trimmed, null);
        }

        return StringUtils.removeEnd(trimmed, "/");
    }

    private static IllegalArgumentException invalidUrl(String rendered, Exception cause) {
        String message = "Invalid `url` `" + rendered + "`, expected a URI with a scheme and a host, e.g. `http://localhost:" + DEFAULT_PORT + "`";

        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }

    /** Custom headers configured on the task or trigger, already rendered. */
    public static Map<String, String> renderedHeaders(RunContext runContext, Property<Map<String, String>> headers) throws IllegalVariableEvaluationException {
        return runContext.render(headers).asMap(String.class, String.class);
    }

    /**
     * Resolves an endpoint against the configured Quickwit URL.
     *
     * @param url base URL of the cluster, for example {@code https://quickwit.example.com:7280}
     * @param path API path without the {@code api/v1} prefix, for example {@code indexes/hdfs-logs}
     * @param query optional query parameters, serialized as a query string
     */
    public static URI endpoint(String url, String path, Map<String, ?> query) {
        String base = StringUtils.removeEnd(StringUtils.trimToEmpty(url), "/") + "/" + API_PREFIX + "/" + StringUtils.stripStart(path, "/");

        if (query == null || query.isEmpty()) {
            return URI.create(base);
        }

        String queryString = query.entrySet()
            .stream()
            .filter(e -> e.getValue() != null)
            .map(e -> e.getKey() + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));

        return URI.create(queryString.isEmpty() ? base : base + "?" + queryString);
    }

    public static URI endpoint(String url, String path) {
        return endpoint(url, path, Map.of());
    }

    /**
     * URL-encodes a path segment, as required for index and source IDs containing special characters.
     *
     * @see <a href="https://quickwit.io/docs/reference/rest-api#parameters">Parameters</a>
     */
    public static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Creates a request carrying the configured headers. */
    public static HttpRequest.HttpRequestBuilder request(String method, URI uri, Map<String, String> headers, HttpRequest.RequestBody body) {
        HttpRequest.HttpRequestBuilder builder = HttpRequest.builder()
            .method(method)
            .uri(uri)
            .addHeader("Accept", "application/json");

        headers.forEach(builder::addHeader);

        return builder.body(body);
    }

    /** Builds a POST request carrying a JSON body, the standard shape for the Quickwit write APIs. */
    public static HttpRequest jsonRequest(String url, String path, Map<String, String> headers, Object body) {
        return request("POST", endpoint(url, path), headers, HttpRequest.JsonRequestBody.of(body)).build();
    }

    /**
     * Runs a search and returns the parsed response.
     *
     * @param startTimestampOverride replaces the configured start timestamp when non-null
     */
    public static SearchResult search(HttpClient client, HttpRequest.HttpRequestBuilder request, SearchQuery query, Long startTimestampOverride) throws Exception {
        // POST rather than GET: the body keeps list parameters (search_field, sort_by) and the `aggs`
        // object as proper JSON instead of the comma-separated form the query string requires.
        HttpRequest searchRequest = request
            .body(HttpRequest.JsonRequestBody.of(query.toBody(startTimestampOverride)))
            .build();

        return execute(client, searchRequest, SearchResult.class, "search on index '" + query.index() + "'");
    }

    /**
     * Executes a request and returns the deserialized response body.
     *
     * @param operation human readable operation name, used to turn a failure into an actionable message
     * @throws IllegalStateException when Quickwit answers 2xx but sends no parsable body
     */
    public static <T> T execute(HttpClient client, HttpRequest request, Class<T> type, String operation) throws Exception {
        try {
            var response = client.request(request, type);

            T body = response.getBody();
            if (body == null) {
                throw new IllegalStateException(
                    "Quickwit " + operation + " returned an empty body with HTTP " + response.getStatus().getCode() +
                        ", expected a JSON payload. Check that `url` points at the Quickwit REST API (default port " + DEFAULT_PORT + ")."
                );
            }

            return body;
        } catch (HttpClientResponseException e) {
            throw failure(e, operation);
        }
    }

    /**
     * Executes a request whose response is a JSON array and converts it to typed models.
     */
    public static <T> List<T> executeList(HttpClient client, HttpRequest request, Class<T> type, String operation) throws Exception {
        List<?> raw = execute(client, request, List.class, operation);

        return raw.stream()
            .map(item ->
            {
                try {
                    return JacksonMapper.cast(item, type);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Unable to parse the Quickwit response of " + operation + " as " + type.getSimpleName(), e);
                }
            })
            .toList();
    }

    /**
     * Executes a request against an endpoint that legitimately answers with an empty body, such as
     * clearing an index, toggling a source or resetting a checkpoint.
     */
    public static void executeIgnoringBody(HttpClient client, HttpRequest request, String operation) throws Exception {
        try {
            client.request(request, String.class);
        } catch (HttpClientResponseException e) {
            throw failure(e, operation);
        }
    }

    private static IllegalStateException failure(HttpClientResponseException e, String operation) {
        int status = e.getResponse() != null && e.getResponse().getStatus() != null ? e.getResponse().getStatus().getCode() : 0;

        return new IllegalStateException(
            "Quickwit " + operation + " failed with HTTP " + status + ": " + failureMessage(e),
            e
        );
    }

    /**
     * Extracts Quickwit's {@code message} field from a failed response.
     *
     * <p>Quickwit documents failed requests as {@code 4xx} with a JSON body holding a
     * {@code message} field describing the error.
     *
     * @see <a href="https://quickwit.io/docs/reference/rest-api#error-handling">Error handling</a>
     */
    public static String failureMessage(HttpClientResponseException exception) {
        String message = exception.getMessage();

        int marker = message == null ? -1 : message.indexOf(BODY_MARKER);
        if (marker < 0) {
            return message;
        }

        String body = message.substring(marker + BODY_MARKER.length()).strip();
        try {
            Object quickwitMessage = JacksonMapper.toMap(body).get("message");
            return quickwitMessage != null ? String.valueOf(quickwitMessage) : body;
        } catch (Exception e) {
            // not a JSON error object: surface the raw body rather than a parsing failure
            return body;
        }
    }

    public static String requireNonBlank(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("`" + field + "` is required and cannot be blank");
        }

        return value;
    }
}