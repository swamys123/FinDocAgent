package com.findoc.util;

import java.util.UUID;

public final class TenantContext {
    private static final ThreadLocal<UUID> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId, UUID userId) {
        TENANT.set(tenantId);
        USER.set(userId);
    }

    public static UUID tenantId() {
        UUID tenantId = TENANT.get();
        if (tenantId == null) throw new IllegalStateException("No tenant in context");
        return tenantId;
    }

    public static UUID tenantIdOrNull() {
        return TENANT.get();
    }

    public static UUID userId() {
        UUID userId = USER.get();
        if (userId == null) throw new IllegalStateException("No user in context");
        return userId;
    }

    public static UUID userIdOrNull() {
        return USER.get();
    }

    public static void clear() {
        TENANT.remove();
        USER.remove();
    }
}
