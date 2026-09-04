package com.findoc.messaging;

public interface IngestionProducer {
    void publish(IngestionJob job);
}