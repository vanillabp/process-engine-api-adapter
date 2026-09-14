package io.vanillabp.pea.wiring;

import java.util.List;

/**
 * The two sentences a delivery which cannot be routed owes a renamed BPMN process.
 * <p>
 * A workflow module may declare a BPMN process id it deploys nothing under, which is what a
 * rename leaves behind: the engine still holds the old model with workflows running on it,
 * while the application brings the new one only. Where the task definitions of that module
 * do not carry the process id, the tasks of those workflows are named like any other and
 * arrive at the subscription of a deployed process. Telling them apart needs the
 * {@link PeaTaskMeta#BPMN_PROCESS_ID} meta entry, and a routing failure which lists the
 * deployed processes alone sends the reader looking at processes which are all innocent.
 * <p>
 * Both task handlers say the same thing about this, so both say it from here.
 */
final class PeaRenamedProcesses {

  private PeaRenamedProcesses() {
    // static helper
  }

  /**
   * What else the delivery may belong to.
   *
   * @param declaredBpmnProcessIds The ids the module declares without deploying a model
   *          under them, sharing the name of the subscription at hand
   * @return The sentence, or an empty String where the module declares no such id
   */
  static String andTheIdsTheModuleOnlyDeclares(
      final List<String> declaredBpmnProcessIds) {

    if (declaredBpmnProcessIds.isEmpty()) {
      return "";
    }
    return (", and the module declares %s without deploying a model, so this may belong to a "
        + "workflow still running under the old id of a renamed process")
        .formatted(declaredBpmnProcessIds);

  }

  /**
   * The way out which asks nothing of the engine and nothing of the models.
   *
   * @param declaredBpmnProcessIds The ids the module declares without deploying a model
   *          under them, sharing the name of the subscription at hand
   * @return The sentence, or an empty String where the module declares no such id
   */
  static String orKeepDeployingTheOldModel(
      final List<String> declaredBpmnProcessIds) {

    return declaredBpmnProcessIds.isEmpty()
        ? ""
        : ", or the old model keeps being deployed under its old id until its workflows have ended";

  }

}
