package io.kestra.plugin.quickwit.search;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.property.Property;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.quickwit.AbstractQuickwitTest;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TriggerTest extends AbstractQuickwitTest {
    private static final String TWO_HITS = """
        {"num_hits": 2, "elapsed_time_micros": 10, "hits": [{"timestamp": 1700000001, "message": "one"}, {"timestamp": 1700000002, "message": "two"}]}
        """;

    private static final String NO_HIT = """
        {"num_hits": 0, "hits": []}
        """;

    @Test
    void firesOnTheFirstPoll(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(TWO_HITS)));

        var context = contextFor(trigger(wireMock));

        assertThat(context.trigger().evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(true));
    }

    @Test
    void doesNotFireWhenTheQueryMatchesNothing(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(NO_HIT)));

        var context = contextFor(trigger(wireMock));

        assertThat(context.trigger().evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(false));
    }

    @Test
    void exposesTheNewDocumentsAndTheWatermark(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(TWO_HITS)));

        var context = contextFor(trigger(wireMock));
        var execution = context.trigger().evaluate(context.conditionContext(), context.triggerContext()).orElseThrow();

        var variables = execution.getTrigger().getVariables();

        assertThat((List<?>) variables.get("documents"), hasSize(2));
        assertThat(variables.get("numHits"), is(2L));
        assertThat(variables.get("watermark"), is(1_700_000_003L));
        assertThat(watermark(context), is("1700000003"));
    }

    @Test
    void storesTheWatermarkSoTheSameDocumentIsNeverDeliveredTwice(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(TWO_HITS)));

        var context = contextFor(trigger(wireMock));

        assertThat(context.trigger().evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(true));

        var stored = watermark(context);
        assertThat("the watermark must be one past the maximum delivered timestamp", stored, is("1700000003"));

        assertEquals(
            stored,
            new String((byte[]) context.conditionContext().getRunContext()
                .namespaceKv(context.triggerContext().getNamespace())
                .getValue(context.stateKey())
                .orElseThrow()
                .value(), StandardCharsets.UTF_8),
            "the watermark must be persisted under the trigger's state key"
        );
    }

    @Test
    void doesNotFireAgainWhileTheQueryKeepsReturningNothingNew(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(TWO_HITS)));

        var context = contextFor(trigger(wireMock));
        var trigger = context.trigger();

        assertThat(trigger.evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(true));

        // second poll: Quickwit only returns documents at or after the stored watermark
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(NO_HIT)));

        assertThat(trigger.evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(false));
    }

    @Test
    void deliversOverflowBeyondMaxHitsOnALaterPoll(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(
            """
                {"num_hits": 2, "hits": [{"timestamp": 1700000001, "message": "one"}]}
                """)));

        var context = contextFor(trigger(wireMock));
        var trigger = context.trigger();

        assertThat(trigger.evaluate(context.conditionContext(), context.triggerContext()).isPresent(), is(true));
        assertThat(watermark(context), is("1700000002"));

        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(
            """
                {"num_hits": 1, "hits": [{"timestamp": 1700000002, "message": "two"}]}
                """)));

        var second = trigger.evaluate(context.conditionContext(), context.triggerContext()).orElseThrow();
        assertThat((List<?>) second.getTrigger().getVariables().get("documents"), hasSize(1));
        assertThat(watermark(context), is("1700000003"));
    }

    @Test
    void failsWhenADocumentMissesTheTimestampField(WireMockRuntimeInfo wireMock) {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(
            """
                {"num_hits": 1, "hits": [{"message": "no timestamp here"}]}
                """)));

        var context = contextFor(trigger(wireMock));

        var thrown = assertThrows(
            IllegalStateException.class,
            () -> context.trigger().evaluate(context.conditionContext(), context.triggerContext())
        );
        assertThat(thrown.getMessage(), is("Document at position 0 misses timestamp field 'timestamp', cannot advance the watermark"));
    }

    @Test
    void searchesOnlyWhatCameAfterTheWatermark(WireMockRuntimeInfo wireMock) throws Exception {
        var trigger = trigger(wireMock);
        var context = contextFor(trigger);

        // a watermark left by a previous poll must be sent as start_timestamp
        putWatermark(context, 1_700_000_000L);

        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(NO_HIT)));
        trigger.evaluate(context.conditionContext(), context.triggerContext());

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/search"))
            .withRequestBody(equalToJson("{\"query\": \"severity:ERROR\", \"start_timestamp\": 1700000000, \"sort_by\": \"+timestamp\"}"))
        );
    }

    @Test
    void sortsAscendingOnTheTimestampField(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/app-logs/search")).willReturn(okJson(NO_HIT)));

        var context = contextFor(trigger(wireMock));
        context.trigger().evaluate(context.conditionContext(), context.triggerContext());

        verify(postRequestedFor(urlPathEqualTo("/api/v1/app-logs/search"))
            .withRequestBody(equalToJson("{\"query\": \"severity:ERROR\", \"sort_by\": \"+timestamp\"}"))
        );
    }

    private Trigger trigger(WireMockRuntimeInfo wireMock) {
        return Trigger.builder()
            .id(IdUtils.create())
            .type(TriggerTest.class.getName())
            .url(Property.ofValue(url(wireMock)))
            .index(Property.ofValue("app-logs"))
            .query(Property.ofValue("severity:ERROR"))
            .timestampField(Property.ofValue("timestamp"))
            .build();
    }

    /** The plugin trigger together with the context pair `TestsUtils.mockTrigger` builds for it. */
    private record Context(ConditionContext conditionContext, io.kestra.core.models.triggers.Trigger triggerContext, Trigger trigger) {
        String stateKey() {
            return triggerContext.getNamespace() + "_" + triggerContext.getFlowId() + "_" + trigger.getId();
        }
    }

    private Context contextFor(Trigger trigger) {
        var entry = TestsUtils.mockTrigger(runContextFactory, trigger);

        return new Context(entry.getKey(), entry.getValue(), trigger);
    }

    private String watermark(Context context) throws Exception {
        return new String((byte[]) context.conditionContext().getRunContext()
            .namespaceKv(context.triggerContext().getNamespace())
            .getValue(context.stateKey())
            .orElseThrow()
            .value(), StandardCharsets.UTF_8).trim();
    }

    private void putWatermark(Context context, long watermark) throws Exception {
        context.conditionContext().getRunContext()
            .namespaceKv(context.triggerContext().getNamespace())
            .put(
                context.stateKey(),
                new KVValueAndMetadata(
                    new KVMetadata("watermark", (Duration) null),
                    Long.toString(watermark).getBytes(StandardCharsets.UTF_8)
                )
            );
    }
}
