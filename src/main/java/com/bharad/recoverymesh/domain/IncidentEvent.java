package com.bharad.recoverymesh.domain;

import java.time.Instant;

public record IncidentEvent(Instant timestamp, String severity, String message) {
}
