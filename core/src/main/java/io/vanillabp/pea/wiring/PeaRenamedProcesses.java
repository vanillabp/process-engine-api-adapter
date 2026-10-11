package io.vanillabp.pea.wiring;

import java.util.List;

/**
 * Two sentences about a renamed BPMN process, for the message of a delivery which cannot
 * be routed.
 * <p>
 * A workflow module may declare a BPMN process id but deploy no model under it. A rename
 * leaves exactly that behind. The engine still holds the old model, and workflows still run
 * on it, but the application only brings the new one. If the task definitions of the module
 * do not carry the process id, the tasks of those old workflows have the same names as any
 * other. They arrive at the subscription of a deployed process. Only the
 * {@link PeaTaskMeta#BPMN_PROCESS_ID} meta entry tells them apart. A routing failure which
 * lists only the deployed processes sends the reader to look at processes which did nothing
 * wrong.
 * <p>
 * Both task handlers say the same thing here, so both take the words from this class.
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
   * The way out which needs no change to the engine and none to the models.
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
