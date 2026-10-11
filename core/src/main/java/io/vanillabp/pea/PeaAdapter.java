package io.vanillabp.pea;

/**
 * Constants shared by all modules of the Process-Engine-API adapter.
 */
public final class PeaAdapter {

  /**
   * The type of this adapter. An application sets it per adapter id with
   * {@code vanillabp.adapters.<id>.type=process-engine-api}. The platform integrations learn
   * about it through the Spring Boot auto-configuration and through the Quarkus capability
   * {@code io.vanillabp.adapter.process-engine-api}.
   */
  public static final String ADAPTER_TYPE = "process-engine-api";

  private PeaAdapter() {
    // only constants, no instances
  }

}
