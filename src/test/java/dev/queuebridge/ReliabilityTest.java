package dev.queuebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    properties = {
      "queuebridge.operator-password=test-operator-password-long",
      "queuebridge.admin-password=test-admin-password-long",
      "queuebridge.demo=true",
      "queuebridge.publisher=false",
      "spring.rabbitmq.listener.simple.auto-startup=false"
    })
@AutoConfigureMockMvc
@Testcontainers
class ReliabilityTest {
  @org.testcontainers.junit.jupiter.Container
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

  @org.testcontainers.junit.jupiter.Container
  static RabbitMQContainer mq = new RabbitMQContainer("rabbitmq:4.1-alpine");

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
    r.add("spring.rabbitmq.host", mq::getHost);
    r.add("spring.rabbitmq.port", mq::getAmqpPort);
    r.add("spring.rabbitmq.username", mq::getAdminUsername);
    r.add("spring.rabbitmq.password", mq::getAdminPassword);
  }

  @Autowired Orders orders;
  @Autowired Messaging messaging;
  @Autowired RabbitTemplate rabbit;
  @Autowired AmqpAdmin admin;
  @Autowired MockMvc mvc;
  @Autowired RabbitListenerEndpointRegistry listeners;

  @BeforeEach
  void clean() {
    listeners.stop();
    admin.initialize();
    admin.purgeQueue(Topology.QUEUE);
    admin.purgeQueue("queuebridge.dead.v1");
    orders.db.execute("TRUNCATE audit,attempts,inbox,outbox,orders CASCADE");
  }

  UUID submit() {
    return orders.submit(new Orders.Request(UUID.randomUUID().toString(), "SENSOR-KIT", 3));
  }

  Messaging.Event event(UUID id) {
    return new Messaging.Event(
        1,
        orders.db.queryForObject(
            "SELECT event_id FROM outbox WHERE order_id=? LIMIT 1", UUID.class, id),
        id);
  }

  void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(100);
    }
    fail("Condition not reached within 15 seconds");
  }

  @Test
  void validationAndDecimals() {
    try (var v = Validation.buildDefaultValidatorFactory()) {
      assertFalse(v.getValidator().validate(new Orders.Request("bad", "SENSOR-KIT", 0)).isEmpty());
    }
    var id = submit();
    assertEquals(new BigDecimal("149.70"), orders.get(id).get("total"));
    assertThrows(
        Exception.class, () -> orders.submit(new Orders.Request("valid-key", "UNKNOWN", 1)));
  }

  @Test
  void idempotencyAndConflict() {
    var r = new Orders.Request("repeat-key", "CABLE-SET", 2);
    UUID id = orders.submit(r);
    assertEquals(id, orders.submit(r));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> orders.submit(new Orders.Request(r.key(), r.product(), 3)));
    assertEquals(1, orders.db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void concurrentIdempotency() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<UUID>>();
      for (int i = 0; i < 16; i++)
        tasks.add(() -> orders.submit(new Orders.Request("concurrent-key", "CABLE-SET", 1)));
      var ids = new HashSet<UUID>();
      for (var future : pool.invokeAll(tasks)) ids.add(future.get());
      assertEquals(1, ids.size());
      assertEquals(1, orders.db.queryForObject("SELECT count(*) FROM outbox", Integer.class));
    }
  }

  @Test
  void atomicOrderOutbox() {
    orders.db.execute("ALTER TABLE outbox ADD CONSTRAINT reject_test CHECK(false) NOT VALID");
    try {
      assertThrows(Exception.class, this::submit);
      assertEquals(0, orders.db.queryForObject("SELECT count(*) FROM orders", Integer.class));
    } finally {
      orders.db.execute("ALTER TABLE outbox DROP CONSTRAINT reject_test");
    }
  }

  @Test
  void confirmedPublishAndDuplicateOutcome() throws Exception {
    UUID id = submit();
    var e = event(id);
    messaging.publishOne();
    assertNotNull(orders.db.queryForObject("SELECT published_at FROM outbox", Object.class));
    listeners.start();
    await(() -> orders.get(id).get("status").equals("FULFILLED"));
    rabbit.convertAndSend(
        "queuebridge.events",
        "order.v1",
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(e));
    await(
        () ->
            orders.db.queryForObject(
                    "SELECT count(*) FROM attempts WHERE outcome='DUPLICATE'", Integer.class)
                == 1);
    assertEquals(1, orders.db.queryForObject("SELECT count(*) FROM inbox", Integer.class));
  }

  @Test
  void unroutablePublishRetained() {
    submit();
    admin.removeBinding(
        new Binding(
            Topology.QUEUE, Binding.DestinationType.QUEUE, "queuebridge.events", "order.v1", null));
    try {
      messaging.publishOne();
      assertNull(orders.db.queryForObject("SELECT published_at FROM outbox", Object.class));
      assertEquals(
          1, orders.db.queryForObject("SELECT publish_attempts FROM outbox", Integer.class));
    } finally {
      admin.declareBinding(
          new Binding(
              Topology.QUEUE,
              Binding.DestinationType.QUEUE,
              "queuebridge.events",
              "order.v1",
              null));
    }
  }

  @Test
  void rollbackThenBrokerRedelivery() throws Exception {
    UUID id = submit();
    orders.db.execute("ALTER TABLE attempts ADD CONSTRAINT rollback_test CHECK(false) NOT VALID");
    messaging.publishOne();
    listeners.start();
    Thread.sleep(800);
    listeners.stop();
    assertEquals("ACCEPTED", orders.get(id).get("status"));
    assertEquals(0, orders.db.queryForObject("SELECT count(*) FROM inbox", Integer.class));
    orders.db.execute("ALTER TABLE attempts DROP CONSTRAINT rollback_test");
    listeners.start();
    await(() -> orders.get(id).get("status").equals("FULFILLED"));
  }

  @Test
  void boundedRetriesAndDeadLetters() throws Exception {
    UUID id = submit();
    orders.admin(id, "admin", "exercise retry exhaustion", "EXHAUST", true);
    listeners.start();
    for (int i = 0; i < 3; i++) {
      orders.db.update("UPDATE outbox SET available_at=now() WHERE published_at IS NULL");
      messaging.publishOne();
      final int expected = i + 1;
      await(
          () ->
              orders.db.queryForObject("SELECT count(*) FROM attempts", Integer.class) >= expected);
    }
    await(() -> orders.get(id).get("status").equals("FAILED"));
    await(() -> admin.getQueueInfo("queuebridge.dead.v1").getMessageCount() == 1);
    assertEquals(3, orders.db.queryForObject("SELECT count(*) FROM attempts", Integer.class));
    assertEquals(
        0,
        orders.db.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class));
  }

  @Test
  void transientBackoffAndBusinessRejection() {
    UUID id = submit();
    orders.admin(id, "admin", "test transient", "TRANSIENT", true);
    assertFalse(messaging.process(event(id)));
    assertEquals("ACCEPTED", orders.get(id).get("status"));
    assertTrue(
        orders.db.queryForObject(
                "SELECT count(*) FROM outbox WHERE available_at>now()", Integer.class)
            > 0);
    assertFalse(messaging.process(event(id)));
    assertEquals("FULFILLED", orders.get(id).get("status"));
    UUID rejected = submit();
    orders.admin(rejected, "admin", "test rejection", "PERMANENT", true);
    messaging.process(event(rejected));
    assertEquals("REJECTED", orders.get(rejected).get("status"));
  }

  @Test
  void malformedMessageDeadLetter() throws Exception {
    listeners.start();
    rabbit.convertAndSend("queuebridge.events", "order.v1", "invalid");
    await(() -> admin.getQueueInfo("queuebridge.dead.v1").getMessageCount() == 1);
  }

  @Test
  void replayAuthorizationCsrfAndBounds() throws Exception {
    UUID id = submit();
    orders.db.update("UPDATE orders SET status='FAILED' WHERE id=?", id);
    String url = "/admin/orders/" + id;
    mvc.perform(
            post(url)
                .with(user("operator").roles("OPERATOR"))
                .with(csrf())
                .param("reason", "test")
                .param("action", "REPLAY"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(url)
                .with(user("admin").roles("ADMIN"))
                .param("reason", "test")
                .param("action", "REPLAY"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(url)
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .param("reason", "reviewed failed delivery")
                .param("action", "REPLAY"))
        .andExpect(status().is3xxRedirection());
    assertEquals(1, orders.db.queryForObject("SELECT count(*) FROM audit", Integer.class));
    orders.db.update("UPDATE orders SET status='FAILED' WHERE id=?", id);
    orders.admin(id, "admin", "second replay", "REPLAY", true);
    orders.db.update("UPDATE orders SET status='FAILED' WHERE id=?", id);
    assertThrows(Exception.class, () -> orders.admin(id, "admin", "third replay", "REPLAY", true));
    assertThrows(
        Exception.class, () -> orders.admin(id, "admin", "not allowed", "TRANSIENT", false));
  }

  @Test
  void apiValidationAndBrowserRendering() throws Exception {
    mvc.perform(
            post("/api/orders")
                .with(user("operator").roles("OPERATOR"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"key\":\"short\",\"product\":\"SENSOR-KIT\",\"quantity\":0}"))
        .andExpect(status().isBadRequest());
    UUID id = submit();
    mvc.perform(get("/").with(user("operator").roles("OPERATOR"))).andExpect(status().isOk());
    mvc.perform(get("/orders/" + id).with(user("operator").roles("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void overlappingPublishersDoNotShareRows() throws Exception {
    submit();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          pool.invokeAll(
              List.<Callable<Void>>of(
                  () -> {
                    messaging.publishOne();
                    return null;
                  },
                  () -> {
                    messaging.publishOne();
                    return null;
                  }));
      for (var future : futures) future.get();
    }
    assertEquals(
        1, orders.db.queryForObject("SELECT sum(publish_attempts) FROM outbox", Integer.class));
    assertNotNull(rabbit.receive(Topology.QUEUE, 3000));
    assertNull(rabbit.receive(Topology.QUEUE, 300));
  }

  @Test
  void authorizedReplayRecoversThroughBroker() throws Exception {
    UUID id = submit();
    orders.admin(id, "admin", "simulate exhaustion", "EXHAUST", true);
    var original = event(id);
    messaging.process(original);
    messaging.process(original);
    assertTrue(messaging.process(original));
    orders.db.update("UPDATE outbox SET published_at=now() WHERE order_id=?", id);
    orders.admin(id, "admin", "reviewed and recover", "REPLAY", true);
    assertEquals("NORMAL", orders.get(id).get("scenario"));
    UUID replayEvent =
        orders.db.queryForObject(
            "SELECT event_id FROM outbox WHERE published_at IS NULL", UUID.class);
    assertNotEquals(original.eventId(), replayEvent);
    messaging.publishOne();
    listeners.start();
    await(() -> orders.get(id).get("status").equals("FULFILLED"));
    assertEquals(2, orders.db.queryForObject("SELECT count(*) FROM inbox", Integer.class));
    assertEquals(2, orders.db.queryForObject("SELECT count(*) FROM audit", Integer.class));
  }

  @Test
  void oversizedJsonRejected() throws Exception {
    mvc.perform(
            post("/api/orders")
                .with(user("operator").roles("OPERATOR"))
                .with(csrf())
                .contentType("application/json")
                .content("x".repeat(16385)))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void actualPasswordLogin() throws Exception {
    mvc.perform(
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders
                .formLogin()
                .user("operator")
                .password("test-operator-password-long"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/"));
    mvc.perform(
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders
                .formLogin()
                .user("operator")
                .password("wrong-password"))
        .andExpect(redirectedUrl("/login?error"));
  }
}
