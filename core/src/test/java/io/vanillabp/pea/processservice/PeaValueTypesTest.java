package io.vanillabp.pea.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter tells the startup check about a value type. It knows less than the
 * other two and says so, and the README says the same thing in prose.
 */
@ExtendWith(SuppressOutputExtension.class)
@DisplayName("What the engine behind the Process-Engine-API does with a value type")
public class PeaValueTypesTest {

  @Test
  @DisplayName("a text, a boolean and an enum survive")
  public void theTypesEveryEngineCarriesSurvive() {

    for (final var type : new Class<?>[]{
        String.class, Boolean.class, boolean.class, ValueDirection.class
    }) {
      assertEquals(
          ValueTypeVerdict.Kind.SURVIVES,
          PeaValueTypes.verdictFor(type, ValueDirection.TO_BPMS).kind(),
          type.getName());
    }

  }

  @Test
  @DisplayName("everything else is answered with cannot say, in both directions")
  public void everythingElseIsUnknown() {

    for (final var direction : ValueDirection.values()) {
      final var verdict = PeaValueTypes.verdictFor(BigDecimal.class, direction);
      assertEquals(ValueTypeVerdict.Kind.CANNOT_SAY, verdict.kind(), direction.name());
      assertNotNull(verdict.explanation());
    }

  }

  @Test
  @DisplayName("the outbound answer also names the expression language reading the value")
  public void theOutboundAnswerNamesTheExpressionLanguage() {

    assertEquals(
        true,
        PeaValueTypes
            .verdictFor(BigDecimal.class, ValueDirection.TO_BPMS)
            .explanation()
            .contains("expression language"));

  }

}
