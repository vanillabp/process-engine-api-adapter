package io.vanillabp.pea;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;

/**
 * The core's name-clash avoidance, configured the way an application configures it.
 * <p>
 * The real service rather than a double, because the form an identifier reaches the engine
 * in is what the core composes: a test which mocks that composition proves only that it
 * mocked it.
 */
public final class TestScoping {

  private TestScoping() {
    // static helper
  }

  /**
   * @param mode How the adapter avoids name clashes
   * @param workflowModuleIds The workflow modules the application configures
   * @return The core's support for adapter id {@code pea}
   */
  public static NameClashAvoidanceService of(
      final io.vanillabp.integration.adapter.spi.NameClashAvoidance mode,
      final String... workflowModuleIds) {

    final var adapter = AdapterConfigProperties.ofType("process-engine-api");
    adapter.setNameClashAvoidance(mode);
    final var workflowModules = new LinkedHashMap<String, WorkflowModuleAdapterProperties>();
    for (final var workflowModuleId : workflowModuleIds) {
      workflowModules.put(workflowModuleId, new WorkflowModuleAdapterProperties());
    }
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("pea", adapter))
        .prioritizedAdapters(List.of("pea"))
        .workflowModules(workflowModules)
        .build();
    properties.validateAndLink();
    return new NameClashAvoidanceService(properties);

  }

}
