package io.vanillabp.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A BPMN error code is scoped before it reaches the engine, on every path which sends one.
 * <p>
 * The rule was broken once, on two paths, and fixed by hand. The second path came to light
 * only because somebody asked whether there was one. A test for each path would not have
 * found it. The path which forgets the scoping is the path nobody wrote a test for. So this
 * test reads the sources and asks the question of every call site, including the ones added
 * later.
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
