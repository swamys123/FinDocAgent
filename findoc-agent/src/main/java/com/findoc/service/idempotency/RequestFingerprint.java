package com.findoc.service.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class RequestFingerprint {
    private final ObjectMapper objectMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String json(String endpoint, Object request) {
        try {
            MessageDigest digest = sha256();
            updateField(digest, endpoint.getBytes(StandardCharsets.UTF_8));
            updateField(digest, objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to fingerprint idempotent request", exception);
        }
    }

    public String upload(String endpoint, String filename, String contentType, byte[] content) {
        MessageDigest digest = sha256();
        updateField(digest, endpoint.getBytes(StandardCharsets.UTF_8));
        updateField(digest, bytes(filename));
        updateField(digest, bytes(contentType));
        updateField(digest, content);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] bytes(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static void updateField(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
            .putInt(value == null ? -1 : value.length)
            .array());
        if (value != null) {
            digest.update(value);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
