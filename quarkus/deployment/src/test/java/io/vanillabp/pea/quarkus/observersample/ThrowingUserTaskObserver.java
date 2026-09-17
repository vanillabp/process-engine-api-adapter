package io.vanillabp.pea.quarkus.observersample;

import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The broken one, registered next to {@link RecordingUserTaskObserver}: what the recording
 * observer sees is what an observer which throws costs the others, whichever order ArC lists
 * the two beans in.
 * <p>
 * It breaks only where a test says so. Since a failing observer fails the delivery, a test
 * about anything else would have to catch that failure for no reason.
 */
@ApplicationScoped
public class ThrowingUserTaskObserver implements PeaUserTaskObserver {

  public static boolean broken = false;

  @Override
  public void userTaskDelivered(
      final PeaUserTaskObservation observation) {

    boom();

  }

  @Override
  public void userTaskTerminated(
      final PeaUserTaskObservation observation) {

    boom();

  }

  private void boom() {

    if (broken) {
      throw new IllegalStateException("boom-observer");
    }

  }

}
