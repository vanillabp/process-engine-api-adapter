package io.vanillabp.pea.quarkus.observersample;

import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The broken observer, registered next to {@link RecordingUserTaskObserver}. What the
 * recording observer sees is what an observer which throws costs the others, in whatever
 * order ArC lists the two beans.
 * <p>
 * It breaks only where a test says so. A failing observer makes the delivery fail. So a test
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
