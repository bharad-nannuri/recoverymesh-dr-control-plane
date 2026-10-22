package com.bharad.recoverymesh.service;

import com.bharad.recoverymesh.domain.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RecoveryCoordinator {
    private final DatabaseNode primary;
    private final DatabaseNode secondary;
    private final long replicationDelayMs;
    private final long failoverDelayMs;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private final Queue<TransactionRecord> queuedWrites = new ConcurrentLinkedQueue<>();
    private final Deque<IncidentEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicReference<Site> activeSite = new AtomicReference<>(Site.PRIMARY);
    private final AtomicReference<RecoveryMode> mode = new AtomicReference<>(RecoveryMode.NORMAL);
    private final AtomicLong totalAccepted = new AtomicLong();
    private final AtomicLong failoverCount = new AtomicLong();
    private final AtomicLong lastRtoMs = new AtomicLong();
    private volatile String lastAction = "System initialized";

    public RecoveryCoordinator(
            @Qualifier("primaryJdbc") JdbcTemplate primaryJdbc,
            @Qualifier("secondaryJdbc") JdbcTemplate secondaryJdbc,
            @Value("${recoverymesh.replication-delay-ms}") long replicationDelayMs,
            @Value("${recoverymesh.failover-delay-ms}") long failoverDelayMs) {
        this.primary = new DatabaseNode(Site.PRIMARY, primaryJdbc);
        this.secondary = new DatabaseNode(Site.SECONDARY, secondaryJdbc);
        this.replicationDelayMs = replicationDelayMs;
        this.failoverDelayMs = failoverDelayMs;
    }

    @PostConstruct
    public void initialize() {
        primary.initialize();
        secondary.initialize();
        event("INFO", "RecoveryMesh online. PRIMARY is active; SECONDARY is warm standby.");
    }

    public synchronized TransactionRecord submit(TransactionRequest request) {
        TransactionRecord tx = new TransactionRecord(
                UUID.randomUUID(),
                normalized(request.shipmentId(), "SHP-" + String.format("%05d", totalAccepted.get() + 1)),
                normalized(request.customer(), "Demo Customer"),
                normalized(request.origin(), "Memphis, TN"),
                normalized(request.destination(), "Dallas, TX"),
                request.amount() == null ? new BigDecimal("2450.00") : request.amount(),
                Instant.now(), activeSite.get());

        if (mode.get() == RecoveryMode.FAILING_OVER) {
            queuedWrites.add(tx);
            totalAccepted.incrementAndGet();
            event("WARN", "Write queued during failover: " + tx.shipmentId());
            return tx;
        }

        DatabaseNode active = node(activeSite.get());
        if (!active.isHealthy()) {
            queuedWrites.add(tx);
            totalAccepted.incrementAndGet();
            if (activeSite.get() == Site.PRIMARY) beginPrimaryFailover();
            event("WARN", "Active database unavailable; write queued: " + tx.shipmentId());
            return tx;
        }

        active.write(tx);
        totalAccepted.incrementAndGet();
        replicateAsync(tx, opposite(activeSite.get()));
        lastAction = tx.shipmentId() + " accepted by " + activeSite.get();
        return tx;
    }

    public synchronized void failPrimary() {
        primary.injectFault();
        event("CRITICAL", "Fault injected: PRIMARY database is unreachable.");
        if (activeSite.get() == Site.PRIMARY) beginPrimaryFailover();
    }

    private void beginPrimaryFailover() {
        if (mode.get() == RecoveryMode.FAILING_OVER || activeSite.get() == Site.SECONDARY) return;
        mode.set(RecoveryMode.FAILING_OVER);
        long started = System.nanoTime();
        lastAction = "Automatic failover in progress";
        event("WARN", "Health check failed. Starting automatic failover to SECONDARY.");

        executor.schedule(() -> {
            if (!secondary.isHealthy()) {
                event("CRITICAL", "Failover failed: SECONDARY database is unavailable.");
                return;
            }
            activeSite.set(Site.SECONDARY);
            mode.set(RecoveryMode.DR_ACTIVE);
            failoverCount.incrementAndGet();
            lastRtoMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            lastAction = "Traffic switched to SECONDARY";
            event("INFO", "Failover complete. SECONDARY is now accepting writes.");
            drainQueuedWrites();
        }, failoverDelayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void restorePrimary() {
        primary.restore();
        mode.set(activeSite.get() == Site.SECONDARY ? RecoveryMode.RECOVERING : RecoveryMode.NORMAL);
        lastAction = "PRIMARY restored; reconciliation required";
        event("INFO", "PRIMARY connectivity restored. Starting data reconciliation.");
        executor.execute(this::reconcile);
    }

    public synchronized void reconcile() {
        if (!primary.isHealthy() || !secondary.isHealthy()) {
            event("WARN", "Reconciliation skipped because both databases are not healthy.");
            return;
        }
        try {
            List<TransactionRecord> primaryRows = primary.list();
            List<TransactionRecord> secondaryRows = secondary.list();
            Map<UUID, TransactionRecord> canonical = new LinkedHashMap<>();
            primaryRows.forEach(tx -> canonical.put(tx.id(), tx));
            secondaryRows.forEach(tx -> canonical.put(tx.id(), tx));
            canonical.values().forEach(tx -> {
                primary.write(tx);
                secondary.write(tx);
            });
            if (activeSite.get() == Site.SECONDARY) {
                mode.set(RecoveryMode.READY_TO_FAILBACK);
                lastAction = "Databases synchronized; ready for controlled failback";
                event("INFO", "Reconciliation complete. PRIMARY and SECONDARY are in sync.");
            } else {
                mode.set(RecoveryMode.NORMAL);
            }
        } catch (RuntimeException ex) {
            event("CRITICAL", "Reconciliation failed: " + ex.getMessage());
        }
    }

    public synchronized void failback() {
        if (!primary.isHealthy()) throw new IllegalStateException("PRIMARY is not healthy");
        if (!secondary.isHealthy()) throw new IllegalStateException("SECONDARY is not healthy");
        if (!inSync()) throw new IllegalStateException("Databases are not synchronized");
        activeSite.set(Site.PRIMARY);
        mode.set(RecoveryMode.NORMAL);
        lastAction = "Controlled failback completed";
        event("INFO", "Controlled failback complete. PRIMARY is active; SECONDARY returned to standby.");
    }

    public synchronized void reset() {
        primary.restore();
        secondary.restore();
        primary.clear();
        secondary.clear();
        queuedWrites.clear();
        events.clear();
        activeSite.set(Site.PRIMARY);
        mode.set(RecoveryMode.NORMAL);
        totalAccepted.set(0);
        failoverCount.set(0);
        lastRtoMs.set(0);
        lastAction = "Demo reset";
        event("INFO", "Demo reset. PRIMARY is active; SECONDARY is warm standby.");
    }

    public List<TransactionView> transactions() {
        Map<UUID, MutablePresence> merged = new LinkedHashMap<>();
        safeList(primary).forEach(tx -> merged.computeIfAbsent(tx.id(), id -> new MutablePresence(tx)).primary = true);
        safeList(secondary).forEach(tx -> merged.computeIfAbsent(tx.id(), id -> new MutablePresence(tx)).secondary = true);
        return merged.values().stream()
                .sorted(Comparator.comparing((MutablePresence p) -> p.tx.createdAt()).reversed())
                .limit(200)
                .map(p -> new TransactionView(
                        p.tx.id(), p.tx.shipmentId(), p.tx.customer(), p.tx.origin(), p.tx.destination(),
                        p.tx.amount(), p.tx.createdAt(), p.tx.acceptedBy(), p.primary, p.secondary))
                .toList();
    }

    public SystemStatus status() {
        boolean pHealthy = primary.isHealthy();
        boolean sHealthy = secondary.isHealthy();
        long pCount = safeCount(primary);
        long sCount = safeCount(secondary);
        long lag = pCount >= 0 && sCount >= 0 ? Math.abs(pCount - sCount) : -1;
        return new SystemStatus(
                activeSite.get(), mode.get(), pHealthy, sHealthy, pCount, sCount, lag,
                queuedWrites.size(), totalAccepted.get(), failoverCount.get(), lastRtoMs.get(),
                pHealthy && sHealthy && pCount == sCount, lastAction);
    }

    public List<IncidentEvent> events() {
        return events.stream().limit(100).toList();
    }

    public List<TransactionRecord> generate(int count) {
        int safeCount = Math.max(1, Math.min(count, 500));
        List<TransactionRecord> generated = new ArrayList<>(safeCount);
        String[] cities = {"Memphis, TN", "Dallas, TX", "Nashville, TN", "Atlanta, GA", "Chicago, IL", "Houston, TX"};
        for (int i = 0; i < safeCount; i++) {
            int n = (int) totalAccepted.get() + 1;
            generated.add(submit(new TransactionRequest(
                    "SHP-" + String.format("%05d", n),
                    "Customer " + ((n % 12) + 1),
                    cities[n % cities.length],
                    cities[(n + 2) % cities.length],
                    BigDecimal.valueOf(700 + (n * 37L) % 6000))));
        }
        return generated;
    }

    private void replicateAsync(TransactionRecord tx, Site targetSite) {
        executor.schedule(() -> {
            DatabaseNode target = node(targetSite);
            if (!target.isHealthy()) {
                event("WARN", "Standby replication delayed for " + tx.shipmentId());
                return;
            }
            try {
                target.write(tx);
            } catch (RuntimeException ex) {
                event("WARN", "Standby replication failed for " + tx.shipmentId());
            }
        }, replicationDelayMs, TimeUnit.MILLISECONDS);
    }

    private void drainQueuedWrites() {
        TransactionRecord tx;
        while ((tx = queuedWrites.poll()) != null) {
            TransactionRecord rerouted = new TransactionRecord(
                    tx.id(), tx.shipmentId(), tx.customer(), tx.origin(), tx.destination(), tx.amount(),
                    tx.createdAt(), activeSite.get());
            node(activeSite.get()).write(rerouted);
            replicateAsync(rerouted, opposite(activeSite.get()));
        }
    }

    private DatabaseNode node(Site site) {
        return site == Site.PRIMARY ? primary : secondary;
    }

    private Site opposite(Site site) {
        return site == Site.PRIMARY ? Site.SECONDARY : Site.PRIMARY;
    }

    private List<TransactionRecord> safeList(DatabaseNode node) {
        try { return node.isHealthy() ? node.list() : List.of(); }
        catch (RuntimeException ex) { return List.of(); }
    }

    private long safeCount(DatabaseNode node) {
        try { return node.count(); }
        catch (RuntimeException ex) { return -1; }
    }

    private boolean inSync() {
        long p = safeCount(primary);
        long s = safeCount(secondary);
        return p >= 0 && s >= 0 && p == s;
    }

    private void event(String severity, String message) {
        events.addFirst(new IncidentEvent(Instant.now(), severity, message));
        while (events.size() > 100) events.pollLast();
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static final class MutablePresence {
        private final TransactionRecord tx;
        private boolean primary;
        private boolean secondary;
        private MutablePresence(TransactionRecord tx) { this.tx = tx; }
    }
}
