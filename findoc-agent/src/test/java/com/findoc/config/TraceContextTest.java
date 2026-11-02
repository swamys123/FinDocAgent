package com.findoc.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceContextTest {
    @Test
    void capturesOnlyW3cTraceHeadersFromTheCurrentSpan() {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span span = mock(Span.class);
        io.micrometer.tracing.TraceContext spanContext = mock(io.micrometer.tracing.TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(spanContext);
        doAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(1);
            Propagator.Setter<Map<String, String>> setter = invocation.getArgument(2);
            setter.set(carrier, "traceparent", "00-trace-span-01");
            setter.set(carrier, "tracestate", "vendor=value");
            setter.set(carrier, "baggage", "sensitive=value");
            return null;
        }).when(propagator).inject(eq(spanContext), anyMap(), any());

        TraceContext traceContext = new TraceContext(tracer, propagator);

        assertThat(traceContext.capture())
            .containsEntry("traceparent", "00-trace-span-01")
            .containsEntry("tracestate", "vendor=value")
            .doesNotContainKey("baggage");
    }

    @Test
    void continuesFromPersistedContextAndEndsTheChildSpan() {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        Map<String, String> parent = Map.of("traceparent", "00-trace-span-01");
        when(propagator.extract(eq(parent), any())).thenReturn(builder);
        when(builder.name("findoc.ingestion.outbox.publish")).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);
        TraceContext traceContext = new TraceContext(tracer, propagator);

        String result = traceContext.continueTrace(parent, "findoc.ingestion.outbox.publish", () -> "published");

        assertThat(result).isEqualTo("published");
        verify(scope).close();
        verify(span).end();
    }

    @Test
    void recordsOperationFailuresOnTheSpanAndRethrowsThem() {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        RuntimeException failure = new IllegalStateException("publish failed");
        when(propagator.extract(anyMap(), any())).thenReturn(builder);
        when(builder.name("findoc.ingestion.outbox.publish")).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);
        TraceContext traceContext = new TraceContext(tracer, propagator);

        assertThatThrownBy(() -> traceContext.continueTrace(Map.of(), "findoc.ingestion.outbox.publish",
            (Supplier<Void>) () -> {
                throw failure;
            })).isSameAs(failure);

        verify(span).error(failure);
        verify(span).end();
    }
}
