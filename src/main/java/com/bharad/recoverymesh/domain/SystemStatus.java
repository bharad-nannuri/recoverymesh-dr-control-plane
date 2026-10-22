package com.bharad.recoverymesh.domain;

public record SystemStatus(
        Site activeSite,
        RecoveryMode mode,
        boolean primaryHealthy,
        boolean secondaryHealthy,
        long primaryRecords,
        long secondaryRecords,
        long replicationLag,
        long queuedWrites,
        long totalAccepted,
        long failoverCount,
        long lastRtoMs,
        boolean inSync,
        String lastAction) {
}
