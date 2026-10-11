package io.vanillabp.pea.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.pea.PeaAdapter;

/**
 * Tells the VanillaBP Spring Boot integration that the Process-Engine-API adapter type
 * exists.
 * <p>
 * Spring has to build this configuration very early, before the platform checks the
 * configured adapter types. So it declares no other beans.
 */
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class PeaAdapterConfiguration extends AdapterConfigurationBase {

  /**
   * Spring Boot builds the class while it applies the auto-configuration. Nothing else
   * happens here. The platform reads the adapter type from the instance.
   */
  public PeaAdapterConfiguration() {

  }

  /**
   * The BPMS type an application writes in <code>vanillabp.adapters.&lt;id&gt;.type</code>
   * to configure this adapter.
   */
  public static final String ADAPTER_TYPE = PeaAdapter.ADAPTER_TYPE;

  @Override
  public String getAdapterType() {

    return ADAPTER_TYPE;

  }

}
