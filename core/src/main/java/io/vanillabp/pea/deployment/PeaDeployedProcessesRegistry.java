package io.vanillabp.pea.deployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one {@link PeaDeployedProcesses} per configured adapter id. The deployment service
 * and the process service share it: the first fills it, the second answers the viewer API
 * from it. Both platforms create ONE registry bean and hand the instance of an id to both
 * services.
 */
public class PeaDeployedProcessesRegistry {

  /**
   * Starts out empty. An adapter id gets its record the first time somebody asks for it.
   * That is the deployment service of that id, at boot.
   */
  public PeaDeployedProcessesRegistry() {

  }

  private final Map<String, PeaDeployedProcesses> byAdapterId = new ConcurrentHashMap<>();

  /**
   * The record of one adapter id. Two configured ids never share one. The same BPMN process
   * id may be deployed to both, and then they are two different processes.
   *
   * @param adapterId The adapter id
   * @return The adapter id's record of deployed processes (created on first use)
   */
  public PeaDeployedProcesses forAdapter(
      final String adapterId) {

    return byAdapterId.computeIfAbsent(adapterId, id -> new PeaDeployedProcesses());

  }

}
