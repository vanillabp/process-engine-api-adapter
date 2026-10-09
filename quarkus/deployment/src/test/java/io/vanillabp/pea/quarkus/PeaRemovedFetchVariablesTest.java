package io.vanillabp.pea.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The key <code>fetch-variables</code> was removed in version 2.0. An application which still
 * sets it does not start, and the message names every key which sets it, here one at the
 * adapter level and one at the task level, and says what applies instead.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaRemovedFetchVariablesTest {

  private static final String AT_TASK_LEVEL = "vanillabp.workflow-modules.pea-test-module.workflows.QuarkusTaskProcess.tasks.quarkusHappy.adapters.pea.fetch-variables";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.pea.quarkus.tasksample")
          .addAsResource("tasks/application.yaml", "application.yaml")
          .addAsResource(
              "pea-test-module/processes/tasks/pea-quarkus-task.bpmn",
              "pea-test-module/processes/tasks/pea-quarkus-task.bpmn")
          .addAsResource("META-INF/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("vanillabp.adapters.pea.fetch-variables", "all")
      .overrideConfigKey(AT_TASK_LEVEL, "all")
      .assertException(throwable -> {
        final var causes = new StringBuilder();
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
          causes
              .append(cause.getMessage())
              .append('\n');
        }
        final var message = causes.toString();
        Assertions.assertTrue(
            message.contains(
                "Process-Engine-API adapter 'pea' is configured with 'fetch-variables', which does not exist any more"),
            "expected the guiding startup failure but got:\n"
                + message);
        Assertions.assertTrue(message.contains("vanillabp.adapters.pea.fetch-variables"), message);
        Assertions.assertTrue(message.contains(AT_TASK_LEVEL), message);
        Assertions.assertTrue(message.contains("workflow aggregate"), message);
      });

  @Test
  public void theRemovedKeyEndsTheStart() {
    // never runs, because the start fails as expected
  }

}
