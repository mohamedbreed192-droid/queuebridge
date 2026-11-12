package dev.queuebridge;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
class Errors {
  @ExceptionHandler({ResponseStatusException.class, MethodArgumentNotValidException.class})
  Object invalid(Exception e, HttpServletRequest request) {
    int status = e instanceof ResponseStatusException r ? r.getStatusCode().value() : 400;
    String message =
        e instanceof ResponseStatusException r && r.getReason() != null
            ? r.getReason()
            : "Check product, quantity (1–100), and idempotency key (8–100 letters, digits,"
                + " underscores or hyphens).";
    if (request.getRequestURI().startsWith("/api/"))
      return ResponseEntity.status(status)
          .body(java.util.Map.of("status", status, "message", message));
    var view = new ModelAndView("error");
    view.setStatus(org.springframework.http.HttpStatus.valueOf(status));
    view.addObject("message", message);
    return view;
  }
}
