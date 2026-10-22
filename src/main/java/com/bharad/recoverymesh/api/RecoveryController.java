package com.bharad.recoverymesh.api;

import com.bharad.recoverymesh.domain.*;
import com.bharad.recoverymesh.service.RecoveryCoordinator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RecoveryController {
    private final RecoveryCoordinator coordinator;

    public RecoveryController(RecoveryCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping("/status")
    public SystemStatus status() { return coordinator.status(); }

    @GetMapping("/transactions")
    public List<TransactionView> transactions() { return coordinator.transactions(); }

    @GetMapping("/events")
    public List<IncidentEvent> events() { return coordinator.events(); }

    @PostMapping("/transactions")
    public TransactionRecord submit(@RequestBody TransactionRequest request) { return coordinator.submit(request); }

    @PostMapping("/traffic/generate")
    public Map<String, Object> generate(@RequestParam(defaultValue = "50") int count) {
        List<TransactionRecord> rows = coordinator.generate(count);
        return Map.of("generated", rows.size(), "status", coordinator.status());
    }

    @PostMapping("/control/fail-primary")
    public SystemStatus failPrimary() {
        coordinator.failPrimary();
        return coordinator.status();
    }

    @PostMapping("/control/restore-primary")
    public SystemStatus restorePrimary() {
        coordinator.restorePrimary();
        return coordinator.status();
    }

    @PostMapping("/control/reconcile")
    public SystemStatus reconcile() {
        coordinator.reconcile();
        return coordinator.status();
    }

    @PostMapping("/control/failback")
    public ResponseEntity<?> failback() {
        try {
            coordinator.failback();
            return ResponseEntity.ok(coordinator.status());
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage(), "status", coordinator.status()));
        }
    }

    @PostMapping("/control/reset")
    public SystemStatus reset() {
        coordinator.reset();
        return coordinator.status();
    }
}
