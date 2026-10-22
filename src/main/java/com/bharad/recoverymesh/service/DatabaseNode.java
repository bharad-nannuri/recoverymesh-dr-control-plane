package com.bharad.recoverymesh.service;

import com.bharad.recoverymesh.domain.Site;
import com.bharad.recoverymesh.domain.TransactionRecord;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseNode {
    private final Site site;
    private final JdbcTemplate jdbc;
    private final AtomicBoolean faultInjected = new AtomicBoolean(false);

    public DatabaseNode(Site site, JdbcTemplate jdbc) {
        this.site = site;
        this.jdbc = jdbc;
    }

    public void initialize() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS recovery_transactions (
              id UUID PRIMARY KEY,
              shipment_id VARCHAR(80) NOT NULL,
              customer VARCHAR(160) NOT NULL,
              origin VARCHAR(120) NOT NULL,
              destination VARCHAR(120) NOT NULL,
              amount NUMERIC(14,2) NOT NULL,
              created_at TIMESTAMPTZ NOT NULL,
              accepted_by VARCHAR(20) NOT NULL
            )
            """);
    }

    public void write(TransactionRecord tx) {
        assertAvailable();
        jdbc.update("""
            INSERT INTO recovery_transactions
              (id, shipment_id, customer, origin, destination, amount, created_at, accepted_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              shipment_id = EXCLUDED.shipment_id,
              customer = EXCLUDED.customer,
              origin = EXCLUDED.origin,
              destination = EXCLUDED.destination,
              amount = EXCLUDED.amount,
              created_at = EXCLUDED.created_at,
              accepted_by = EXCLUDED.accepted_by
            """,
            tx.id(), tx.shipmentId(), tx.customer(), tx.origin(), tx.destination(), tx.amount(),
            Timestamp.from(tx.createdAt()), tx.acceptedBy().name());
    }

    public List<TransactionRecord> list() {
        assertAvailable();
        return jdbc.query("SELECT * FROM recovery_transactions ORDER BY created_at DESC", (rs, rowNum) ->
            new TransactionRecord(
                rs.getObject("id", UUID.class),
                rs.getString("shipment_id"),
                rs.getString("customer"),
                rs.getString("origin"),
                rs.getString("destination"),
                rs.getBigDecimal("amount"),
                rs.getTimestamp("created_at").toInstant(),
                Site.valueOf(rs.getString("accepted_by"))
            ));
    }

    public long count() {
        if (!isHealthy()) return -1;
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM recovery_transactions", Long.class);
        return value == null ? 0 : value;
    }

    public void clear() {
        if (!isHealthy()) return;
        jdbc.update("DELETE FROM recovery_transactions");
    }

    public boolean isHealthy() {
        if (faultInjected.get()) return false;
        try {
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            return value != null && value == 1;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public void injectFault() {
        faultInjected.set(true);
    }

    public void restore() {
        faultInjected.set(false);
    }

    private void assertAvailable() {
        if (!isHealthy()) {
            throw new IllegalStateException(site + " database is unavailable");
        }
    }
}
