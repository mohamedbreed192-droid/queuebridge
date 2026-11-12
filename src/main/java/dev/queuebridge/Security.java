package dev.queuebridge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Security {
  @Bean
  BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(
      @Value("${queuebridge.operator-password}") String operator,
      @Value("${queuebridge.admin-password}") String admin,
      BCryptPasswordEncoder encoder) {
    if (operator.length() < 16 || admin.length() < 16 || operator.equals(admin))
      throw new IllegalArgumentException("Provide distinct passwords of at least 16 characters");
    return new InMemoryUserDetailsManager(
        User.withUsername("operator").password(encoder.encode(operator)).roles("OPERATOR").build(),
        User.withUsername("admin")
            .password(encoder.encode(admin))
            .roles("OPERATOR", "ADMIN")
            .build());
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
  SecurityFilterChain chain(HttpSecurity h) throws Exception {
    return h.authorizeHttpRequests(
            a ->
                a.requestMatchers("/style.css", "/app.js", "/login")
                    .permitAll()
                    .requestMatchers("/admin/**", "/actuator/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .hasRole("OPERATOR"))
        .formLogin(f -> f.defaultSuccessUrl("/", true))
        .logout(l -> l.logoutSuccessUrl("/login"))
        .build();
  }
}
