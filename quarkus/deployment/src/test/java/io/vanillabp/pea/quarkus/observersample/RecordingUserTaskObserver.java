package io.vanillabp.pea.quarkus.observersample;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The observer bean of the application. It is a plain CDI bean of the interface of the
 * adapter. Nothing of the application injects it. The deployment services of the adapter get
 * it while they are produced.
 */
@ApplicationScoped
public class RecordingUserTaskObserver implements PeaUserTaskObserver {

  public static final List<PeaUserTaskObservation> DELIVERED = new CopyOnWriteArrayList<>();

  public static final List<PeaUserTaskObservation> TERMINATED = new CopyOnWriteArrayList<>();

  @Override
  public void userTaskDelivered(
      final PeaUserTaskObservation observation) {

    DELIVERED.add(observation);

  }

  @Override
  public void userTaskTerminated(
      final PeaUserTaskObservation observation) {

    TERMINATED.add(observation);

  }

}
