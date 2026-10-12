package io.vanillabp.pea.springboot.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A test application which boots the full VanillaBP Spring Boot integration with the
 * Process-Engine-API adapter on the mock. It has a JPA aggregate and the JDBC outbox for
 * phase two which the platform provides. So {@code ProcessService#startWorkflow} runs the
 * real two-phase start. Phase one ({@code PREFLIGHT_CHECK}) runs inside the transaction.
 * Phase two ({@code SYNC}) runs after the commit, through the outbox.
 */
@SpringBootApplication
public class OutboxTestApplication {

}
