package dev.queuebridge;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Orders {
  public record Request(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,100}") String key,
      @NotBlank @Size(max = 32) String product,
      @Min(1) @Max(100) int quantity) {}

  public static final Map<String, BigDecimal> CATALOG =
      Map.of(
          "SENSOR-KIT",
          new BigDecimal("49.90"),
          "RELAY-PACK",
          new BigDecimal("24.75"),
          "CABLE-SET",
          new BigDecimal("12.50"));
  final JdbcTemplate db;
  final TransactionTemplate tx;

  public Orders(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public UUID submit(Request r) {
    if (!CATALOG.containsKey(r.product()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown synthetic product");
    return tx.execute(
        s -> {
          String fingerprint = r.product() + ":" + r.quantity();
          UUID id = UUID.randomUUID();
          int added =
              db.update(
                  "INSERT INTO orders(id,idempotency_key,fingerprint,product,quantity,total) VALUES"
                      + " (?,?,?,?,?,?) ON CONFLICT(idempotency_key) DO NOTHING",
                  id,
                  r.key(),
                  fingerprint,
                  r.product(),
                  r.quantity(),
                  CATALOG.get(r.product()).multiply(BigDecimal.valueOf(r.quantity())));
          if (added == 0) {
            var old =
                db.queryForMap(
                    "SELECT id,fingerprint FROM orders WHERE idempotency_key=?", r.key());
            if (!old.get("fingerprint").equals(fingerprint))
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "Idempotency key already used with different content");
            return (UUID) old.get("id");
          }
          enqueue(id, UUID.randomUUID(), 0);
          return id;
        });
  }

  void enqueue(UUID order, UUID event, int delay) {
    db.update(
        "INSERT INTO outbox(id,order_id,event_id,available_at) VALUES (?,?,?,now() + (? * interval"
            + " '1 second'))",
        UUID.randomUUID(),
        order,
        event,
        delay);
  }

  public Map<String, Object> get(UUID id) {
    var rows = db.queryForList("SELECT * FROM orders WHERE id=?", id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return rows.getFirst();
  }

  public void admin(UUID id, String actor, String reason, String action, boolean demo) {
    if (reason == null || reason.isBlank() || reason.length() > 300)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Reason required (1–300 characters)");
    tx.executeWithoutResult(
        s -> {
          var o = db.queryForMap("SELECT * FROM orders WHERE id=? FOR UPDATE", id);
          if (action.equals("REPLAY")) {
            if (!o.get("status").equals("FAILED") || (int) o.get("replays") >= 2)
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "Only failed orders can replay, at most twice");
            db.update(
                "UPDATE orders SET status='ACCEPTED',scenario='NORMAL',replays=replays+1 WHERE"
                    + " id=?",
                id);
            enqueue(id, UUID.randomUUID(), 0);
          } else {
            if (!demo) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            if (!o.get("status").equals("ACCEPTED"))
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "Lab requires an accepted order");
            if (action.equals("DUPLICATE")) {
              UUID event =
                  db.queryForObject(
                      "SELECT event_id FROM outbox WHERE order_id=? ORDER BY available_at LIMIT 1",
                      UUID.class,
                      id);
              enqueue(id, event, 0);
            } else if (Set.of("TRANSIENT", "PERMANENT", "EXHAUST").contains(action))
              db.update("UPDATE orders SET scenario=? WHERE id=?", action, id);
            else throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
          }
          db.update(
              "INSERT INTO audit(order_id,actor,action,reason) VALUES (?,?,?,?)",
              id,
              actor,
              action,
              reason);
        });
  }
}
