package dev.queuebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
class Topology {
  static final String QUEUE = "queuebridge.orders.v1";

  @Bean
  Declarables queues() {
    return new Declarables(
        new DirectExchange("queuebridge.events", true, false),
        new DirectExchange("queuebridge.dead", true, false),
        QueueBuilder.durable(QUEUE)
            .deadLetterExchange("queuebridge.dead")
            .deadLetterRoutingKey("dead")
            .build(),
        new org.springframework.amqp.core.Queue("queuebridge.dead.v1", true),
        new Binding(QUEUE, Binding.DestinationType.QUEUE, "queuebridge.events", "order.v1", null),
        new Binding(
            "queuebridge.dead.v1",
            Binding.DestinationType.QUEUE,
            "queuebridge.dead",
            "dead",
            null));
  }
}

@Component
public class Messaging {
  public record Event(int version, UUID eventId, UUID orderId) {}

  final JdbcTemplate db;
  final TransactionTemplate tx;
  final RabbitTemplate rabbit;
  final ObjectMapper json;
  final Orders orders;
  final boolean enabled;

  public Messaging(
      JdbcTemplate db,
      TransactionTemplate tx,
      RabbitTemplate rabbit,
      ObjectMapper json,
      Orders orders,
      @Value("${queuebridge.publisher}") boolean enabled) {
    this.db = db;
    this.tx = tx;
    this.rabbit = rabbit;
    this.json = json;
    this.orders = orders;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    if (enabled) publishOne();
  }

  public void publishOne() {
    tx.executeWithoutResult(
        s -> {
          var rows =
              db.queryForList(
                  "SELECT * FROM outbox WHERE published_at IS NULL AND available_at<=now() ORDER BY"
                      + " available_at LIMIT 1 FOR UPDATE SKIP LOCKED");
          if (rows.isEmpty()) return;
          var row = rows.getFirst();
          UUID id = (UUID) row.get("id");
          try {
            var event = new Event(1, (UUID) row.get("event_id"), (UUID) row.get("order_id"));
            var props = new MessageProperties();
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setContentType("application/json");
            props.setMessageId(event.eventId().toString());
            var correlation = new CorrelationData(id.toString());
            rabbit.send(
                "queuebridge.events",
                "order.v1",
                new Message(json.writeValueAsBytes(event), props),
                correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null)
              throw new IllegalStateException("Broker nack or unroutable event");
            db.update(
                "UPDATE outbox SET"
                    + " published_at=now(),publish_attempts=publish_attempts+1,last_error=NULL"
                    + " WHERE id=?",
                id);
          } catch (Exception e) {
            db.update(
                "UPDATE outbox SET publish_attempts=publish_attempts+1,last_error='Publish"
                    + " unconfirmed; retry scheduled',available_at=now()+interval '5 seconds' WHERE"
                    + " id=?",
                id);
          }
        });
  }

  @RabbitListener(queues = Topology.QUEUE)
  public void receive(Message message, Channel channel) throws Exception {
    long tag = message.getMessageProperties().getDeliveryTag();
    Event event;
    try {
      if (message.getBody().length > 4096) throw new IllegalArgumentException();
      event = json.readValue(message.getBody(), Event.class);
      if (event.version() != 1 || event.eventId() == null || event.orderId() == null)
        throw new IllegalArgumentException();
    } catch (Exception invalid) {
      channel.basicReject(tag, false);
      return;
    }
    try {
      boolean dead = process(event);
      if (dead) channel.basicReject(tag, false);
      else channel.basicAck(tag, false);
    } catch (Exception failure) {
      /* rollback leaves delivery unacknowledged; channel closure triggers broker redelivery */
      channel.close();
    }
  }

  public boolean process(Event event) {
    return Boolean.TRUE.equals(
        tx.execute(
            s -> {
              var rows =
                  db.queryForList("SELECT * FROM orders WHERE id=? FOR UPDATE", event.orderId());
              if (rows.isEmpty()) return true;
              var o = rows.getFirst();
              var seen =
                  db.queryForList("SELECT outcome FROM inbox WHERE event_id=?", event.eventId());
              if (!seen.isEmpty()) {
                attempt(event, "DUPLICATE");
                return seen.getFirst().get("outcome").equals("FAILED");
              }
              if (!o.get("status").equals("ACCEPTED")) {
                attempt(event, "STALE");
                return false;
              }
              int count =
                  db.queryForObject(
                      "SELECT count(*) FROM attempts WHERE event_id=? AND outcome IN"
                          + " ('RETRY','EXHAUSTED')",
                      Integer.class,
                      event.eventId());
              String scenario = (String) o.get("scenario");
              if (scenario.equals("EXHAUST") || (scenario.equals("TRANSIENT") && count == 0)) {
                boolean exhausted = count >= 2;
                attempt(event, exhausted ? "EXHAUSTED" : "RETRY");
                if (exhausted) {
                  db.update(
                      "INSERT INTO inbox(event_id,order_id,outcome) VALUES (?,?,'FAILED')",
                      event.eventId(),
                      event.orderId());
                  db.update("UPDATE orders SET status='FAILED' WHERE id=?", event.orderId());
                  return true;
                }
                orders.enqueue(event.orderId(), event.eventId(), count == 0 ? 5 : 15);
                return false;
              }
              String outcome = scenario.equals("PERMANENT") ? "REJECTED" : "FULFILLED";
              db.update(
                  "INSERT INTO inbox(event_id,order_id,outcome) VALUES (?,?,?)",
                  event.eventId(),
                  event.orderId(),
                  outcome);
              db.update("UPDATE orders SET status=? WHERE id=?", outcome, event.orderId());
              attempt(event, outcome);
              return false;
            }));
  }

  void attempt(Event e, String outcome) {
    db.update(
        "INSERT INTO attempts(event_id,order_id,outcome) VALUES (?,?,?)",
        e.eventId(),
        e.orderId(),
        outcome);
  }
}
