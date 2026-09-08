package io.vanillabp.pea.quarkus.observersample;

import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The broken one, registered next to {@link RecordingUserTaskObserver}: what the recording
 * observer sees is what an observer which throws costs the others, whichever order ArC lists
 * the two beans in.
 */
@ApplicationScoped
public class ThrowingUserTaskObserver implements PeaUserTaskObserver {

  @Override
  public void userTaskDelivered(
      final PeaUserTaskObservation observation) {

    throw new IllegalStateException("boom-observer");

  }

  @Override
  public void userTaskTerminated(
      final PeaUserTaskObservation observation) {

    throw new IllegalStateException("boom-observer");

  }

}
