package com.findoc.service.document;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {
    @Test
    void createsOverlappingChunksWithConfiguredBounds() {
        String text = String.join(" ", java.util.Collections.nCopies(600, "word"));
        var chunks = new ChunkingService().chunk(text);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).split(" ")).hasSize(512);
        assertThat(chunks.get(1).split(" ")).hasSize(138);
    }
}
