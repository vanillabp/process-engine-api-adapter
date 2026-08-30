package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * What this adapter can say about a failed phase-two operation. The API has no
 * typed exceptions, so exactly one family is classifiable - the one the adapter throws
 * itself when its API cannot do what VanillaBP asks.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaPhaseTwoClassificationTest {

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private PeaProcessService<Object> service() {

    return new PeaProcessService<>("pea", engine, engine, engine, engine);

  }

  @Test
  @DisplayName("What the API cannot do is permanent - repeating it would only make noise")
  public void whatTheApiCannotDoIsPermanent() {

    final var service = service();

    // the two cases this adapter throws itself: a signal without a SignalApi, and
    // pushing a changed aggregate into a running instance
    assertFalse(
        service.isPhaseTwoFailureRepeatable(
            new UnsupportedOperationException("no SignalApi")));
    // the outbox hands over what the dispatch caught, so a wrapped cause counts too
    assertFalse(
        service.isPhaseTwoFailureRepeatable(
            new IllegalStateException("dispatching", new UnsupportedOperationException("no SignalApi"))));

  }

  @Test
  @DisplayName("Everything else is repeated, because the API cannot tell refused from unreachable")
  public void everythingElseIsRepeatable() {

    final var service = service();

    assertTrue(
        service.isPhaseTwoFailureRepeatable(
            new CompletionException(new IOException("connection refused"))));
    assertTrue(service.isPhaseTwoFailureRepeatable(new IllegalStateException("the task is gone")));
    assertTrue(service.isPhaseTwoFailureRepeatable(null));

    final var selfReferencing = new RuntimeException("loops") {

      @Override
      public synchronized Throwable getCause() {
        return this;
      }

    };
    assertTrue(service.isPhaseTwoFailureRepeatable(selfReferencing));

  }

}
