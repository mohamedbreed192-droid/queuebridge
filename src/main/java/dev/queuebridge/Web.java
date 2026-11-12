package dev.queuebridge;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class Web {
  final Orders orders;
  final boolean demo;

  public Web(Orders orders, @Value("${queuebridge.demo}") boolean demo) {
    this.orders = orders;
    this.demo = demo;
  }

  @GetMapping("/")
  String home(@RequestParam(defaultValue = "") String q, Model m) {
    m.addAttribute(
        "orders",
        orders.db.queryForList(
            "SELECT * FROM orders WHERE (id::text || ' ' || product || ' ' || status) ILIKE ? ORDER"
                + " BY created_at DESC LIMIT 100",
            "%" + q.substring(0, Math.min(q.length(), 100)) + "%"));
    m.addAttribute(
        "counts",
        orders.db.queryForList(
            "SELECT status,count(*) AS n FROM orders GROUP BY status ORDER BY status"));
    m.addAttribute(
        "pending",
        orders.db.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class));
    m.addAttribute("catalog", Orders.CATALOG);
    m.addAttribute("key", UUID.randomUUID());
    return "index";
  }

  @PostMapping("/orders")
  String submit(@Valid @ModelAttribute Orders.Request request) {
    return "redirect:/orders/" + orders.submit(request);
  }

  @PostMapping("/api/orders")
  @ResponseBody
  ResponseEntity<?> api(@Valid @RequestBody Orders.Request request) {
    return ResponseEntity.ok(orders.get(orders.submit(request)));
  }

  @GetMapping("/orders/{id}")
  String detail(@PathVariable UUID id, Model m, jakarta.servlet.http.HttpServletRequest request) {
    m.addAttribute("order", orders.get(id));
    m.addAttribute(
        "attempts",
        orders.db.queryForList("SELECT * FROM attempts WHERE order_id=? ORDER BY id", id));
    m.addAttribute(
        "events",
        orders.db.queryForList("SELECT * FROM outbox WHERE order_id=? ORDER BY available_at", id));
    m.addAttribute(
        "audit", orders.db.queryForList("SELECT * FROM audit WHERE order_id=? ORDER BY id", id));
    m.addAttribute("demo", demo);
    m.addAttribute("admin", request.isUserInRole("ADMIN"));
    return "detail";
  }

  @PostMapping("/admin/orders/{id}")
  String admin(
      @PathVariable UUID id,
      @RequestParam String reason,
      @RequestParam String action,
      Principal actor) {
    orders.admin(id, actor.getName(), reason, action, demo);
    return "redirect:/orders/" + id;
  }
}
