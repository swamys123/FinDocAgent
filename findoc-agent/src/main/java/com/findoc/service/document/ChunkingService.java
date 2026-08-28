package com.findoc.service.document;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ChunkingService {
    public static final int CHUNK_SIZE_TOKENS = 512;
    public static final int OVERLAP_TOKENS = 50;

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] words = text.trim().split("\\s+");
        List<String> chunks = new ArrayList<>();
        int step = CHUNK_SIZE_TOKENS - OVERLAP_TOKENS;
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(start + CHUNK_SIZE_TOKENS, words.length);
            chunks.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            if (end == words.length) break;
        }
        return chunks;
    }
}
