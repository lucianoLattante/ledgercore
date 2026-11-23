package io.github.lucianolattante.ledgercore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class LedgerCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerCoreApplication.class, args);
    }
}
