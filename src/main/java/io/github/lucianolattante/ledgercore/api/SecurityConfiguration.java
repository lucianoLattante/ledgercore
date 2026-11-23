package io.github.lucianolattante.ledgercore.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    @Profile("demo")
    SecurityFilterChain demoApi(HttpSecurity http) throws Exception {
        // Ledger writes require a custom header, which browsers cannot send cross-origin without CORS.
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Profile("demo")
    UserDetailsService demoUsers(
            @Value("${ledgercore.demo.alice-password}") String alicePassword,
            @Value("${ledgercore.demo.bob-password}") String bobPassword) {
        if (alicePassword.isBlank() || bobPassword.isBlank() || alicePassword.equals(bobPassword)) {
            throw new IllegalArgumentException("Configure distinct nonempty demo passwords");
        }
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password(encoder.encode(alicePassword)).roles("DEMO").build(),
                User.withUsername("bob").password(encoder.encode(bobPassword)).roles("DEMO").build());
    }

    @Bean
    @Profile("!demo")
    SecurityFilterChain disabledApi(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll()).build();
    }
}
