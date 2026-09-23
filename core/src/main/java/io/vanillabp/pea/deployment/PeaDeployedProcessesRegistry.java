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

  /**
   * Starts out empty. An adapter id gets its record the first time somebody asks for it,
   * which is the deployment service of that id at boot.
   */
  public PeaDeployedProcessesRegistry() {

  }

  private final Map<String, PeaDeployedProcesses> byAdapterId = new ConcurrentHashMap<>();

  /**
   * The record of one adapter id. Two configured ids never share one, because the same
   * BPMN process id may be deployed to both and they are different processes.
   *
   * @param adapterId The adapter id
   * @return The adapter id's record of deployed processes (created on first use)
   */
  public PeaDeployedProcesses forAdapter(
      final String adapterId) {

    return byAdapterId.computeIfAbsent(adapterId, id -> new PeaDeployedProcesses());

  }

}
