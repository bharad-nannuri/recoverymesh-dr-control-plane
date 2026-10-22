package com.bharad.recoverymesh.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionView(
        UUID id,
        String shipmentId,
        String customer,
        String origin,
        String destination,
        BigDecimal amount,
        Instant createdAt,
        Site acceptedBy,
        boolean onPrimary,
        boolean onSecondary) {
}
