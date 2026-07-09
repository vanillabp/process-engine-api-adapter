package io.vanillabp.pea;

/**
 * Constants shared by all modules of the Process-Engine-API adapter.
 */
public final class PeaAdapter {

  /**
   * The adapter type of this adapter. Configured per adapter id via
   * {@code vanillabp.adapters.<id>.type=process-engine-api} and announced to the
   * VanillaBP platform integrations (Spring Boot auto-configuration / Quarkus
   * extension capability {@code io.vanillabp.adapter.process-engine-api}).
   */
  public static final String ADAPTER_TYPE = "process-engine-api";

  private PeaAdapter() {
    // constants holder
  }

}
