package com.findoc.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AuthRequest(@NotNull UUID tenantId, @NotBlank String username, @NotBlank String password) {}
