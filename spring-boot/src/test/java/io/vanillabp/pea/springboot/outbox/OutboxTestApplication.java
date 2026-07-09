package io.vanillabp.pea.springboot.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test application booting the full VanillaBP Spring Boot integration with the
 * Process-Engine-API adapter (mock-backed), a JPA aggregate and the gruelbox-based
 * phase-two outbox, so {@code ProcessService#startWorkflow} exercises the real two-phase
 * start: phase one ({@code PREFLIGHT_CHECK}) inside the transaction, phase two
 * ({@code SYNC}) after commit via the outbox.
 */
@SpringBootApplication
public class OutboxTestApplication {

}
