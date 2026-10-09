package io.vanillabp.pea.springboot.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.deployment.DeploymentAutoConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.pea.springboot.PeaAdapterConfiguration;
import io.vanillabp.pea.springboot.TestOutboxConfiguration;
import io.vanillabp.pea.springboot.TestPersistenceConfiguration;
import io.vanillabp.pea.springboot.processservice.PeaAdapterProcessServiceConfiguration;

/**
 * The key <code>fetch-variables</code> was removed in version 2.0. An application which still
 * sets it does not start, and the message names every key which sets it and says what applies
 * instead. The application is the one of {@link OrphanMethodBootTest}, because the start ends
 * before its workflow service matters.
 */
@ExtendWith(SuppressOutputExtension.class)
public class RemovedFetchVariablesBootTest {

  private static final Class<?>[] SOURCES = {
      PeaAdapterConfiguration.class, PeaAdapterProcessServiceConfiguration.class, WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class, DeploymentAutoConfiguration.class, TestPersistenceConfiguration.class, TestOutboxConfiguration.class, OrphanMethodBootTest.OrphanMethodConfiguration.class, OrphanMethodBootTest.OrphanMethodWorkflowService.class
  };

  private static final String AT_TASK_LEVEL = "vanillabp.workflow-modules.pea-test-module.workflows.PeaOrphanProcess.tasks.orphanModelled.adapters.pea.fetch-variables";

  @Test
  @DisplayName("The removed key fetch-variables ends the start, naming every key which sets it")
  public void theRemovedKeyEndsTheStart() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(SOURCES)
            .run(
                "--vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/orphan",
                "--vanillabp.adapters.pea.fetch-variables=all",
                "--"
                    + AT_TASK_LEVEL
                    + "=all")
            .close());

    final var causes = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      causes
          .append(cause.getMessage())
          .append('\n');
    }
    final var message = causes.toString();
    assertTrue(
        message.contains(
            "Process-Engine-API adapter 'pea' is configured with 'fetch-variables', which does not exist any more"),
        "expected the guiding startup failure but got:\n"
            + message);
    assertTrue(message.contains("vanillabp.adapters.pea.fetch-variables"), message);
    assertTrue(message.contains(AT_TASK_LEVEL), message);
    assertTrue(message.contains("workflow aggregate"), message);

  }

}
