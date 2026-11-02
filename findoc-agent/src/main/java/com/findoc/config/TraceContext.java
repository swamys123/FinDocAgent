package com.findoc.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class TraceContext {
    private static final Propagator.Setter<Map<String, String>> SETTER = Map::put;
    private static final Propagator.Getter<Map<String, String>> GETTER = Map::get;

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public Map<String, String> capture() {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan == null) {
            return Map.of();
        }
        Map<String, String> context = new LinkedHashMap<>();
        propagator.inject(currentSpan.context(), context, SETTER);
        context.keySet().retainAll(java.util.Set.of("traceparent", "tracestate"));
        return Map.copyOf(context);
    }

    public <T> T continueTrace(Map<String, String> parentContext, String spanName, Supplier<T> operation) {
        Span.Builder spanBuilder = propagator.extract(parentContext, GETTER);
        Span span = spanBuilder.name(spanName).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return operation.get();
        } catch (RuntimeException exception) {
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
    }
}
