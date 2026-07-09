package io.vanillabp.pea.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.pea.PeaAdapter;

/**
 * Announces the Process-Engine-API adapter type to the VanillaBP Spring Boot integration.
 * <p>
 * This configuration must be constructible very early - before the platform validates the
 * configured adapter types - therefore it declares no other beans (see the dummy adapter
 * template).
 */
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class PeaAdapterConfiguration extends AdapterConfigurationBase {

  public static final String ADAPTER_TYPE = PeaAdapter.ADAPTER_TYPE;

  @Override
  public String getAdapterType() {

    return ADAPTER_TYPE;

  }

}
