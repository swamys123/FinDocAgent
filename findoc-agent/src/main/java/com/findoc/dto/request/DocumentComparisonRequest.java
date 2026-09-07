package com.findoc.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DocumentComparisonRequest(
    @NotNull UUID documentIdA,
    @NotNull UUID documentIdB,
    @NotBlank String aspect
) {
}