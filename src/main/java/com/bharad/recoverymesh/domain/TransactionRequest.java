package com.bharad.recoverymesh.domain;

import java.math.BigDecimal;

public record TransactionRequest(
        String shipmentId,
        String customer,
        String origin,
        String destination,
        BigDecimal amount) {
}
