package io.vanillabp.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Holds the rule that a BPMN error code is scoped before it reaches the engine, on every path
 * which sends one.
 * <p>
 * The rule was broken once and fixed by hand, on two paths, and the second of the two came to
 * light only because somebody asked whether there was one. A test per path would not have found
 * it: the path which forgets the scoping is by definition the path nobody wrote a test for. So
 * this reads the sources instead and asks the question of every call site there is, the ones
 * added after today included.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ErrorCodesReachingTheEngineTest {

  @Test
  @DisplayName("Every error code this adapter sends to the engine goes through the scoping helper")
  public void everyErrorCodeIsScoped() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var offenders = ErrorCodesReachingTheEngine.unscopedErrorCodes(root);

    assertTrue(
        offenders.isEmpty(),
        () -> ErrorCodesReachingTheEngine.describeUnscopedErrorCodes(offenders));

  }

  @Test
  @DisplayName("The scan finds the call sites it is about")
  public void theScanFindsSomething() {

    final var root = CoverageGate.repositoryRoot("coverage.repository.root");

    final var callSites = ErrorCodesReachingTheEngine.callSites(root);

    assertTrue(
        callSites > 0,
        () -> "The check above read the sources below '"
            + root
            + "' and found no place at all where an error code reaches the engine. Either the "
            + "commands were renamed, in which case the check has to learn the new name, or it "
            + "is looking in the wrong directory. Until then it holds nothing.");

  }

}
