package io.vanillabp.pea.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one {@link PeaDeployedProcesses} per configured adapter id - the object
 * the deployment service (which fills it) and the process service (which serves
 * the viewer API from it) share. Both platforms create ONE registry bean and hand
 * the per-id instance to both services.
 */
public class PeaDeployedProcessesRegistry {

  private final Map<String, PeaDeployedProcesses> byAdapterId = new ConcurrentHashMap<>();

  /**
   * @param adapterId The adapter id
   * @return The adapter id's record of deployed processes (created on first use)
   */
  public PeaDeployedProcesses forAdapter(
      final String adapterId) {

    return byAdapterId.computeIfAbsent(adapterId, id -> new PeaDeployedProcesses());

  }

}
