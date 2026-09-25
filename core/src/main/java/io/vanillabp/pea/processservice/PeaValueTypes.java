package io.vanillabp.pea.processservice;

import java.util.Set;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;

/**
 * What the Process-Engine-API does with one Java type, answered for the startup check
 * which asks whether a value survives the way to the engine and back.
 * <p>
 * This adapter knows less than the other two, and it says so. The Process-Engine-API is
 * deliberately engine-agnostic: it takes a payload and gives one back, and which engine
 * sits behind it, how that engine stores a variable and which expression language reads it
 * are all outside the API. So the only types this adapter can speak for are the ones the
 * API itself names, and for everything else the honest answer is that it cannot say.
 * <p>
 * That answer never ends a startup, which is the point of it being allowed at all (see
 * {@link ValueTypeVerdict}). It does cost the developer a warning, and the way out of the
 * warning is a test against the engine that is really used.
 */
public final class PeaValueTypes {

  /**
   * The types the payload of the Process-Engine-API carries as themselves. They are the
   * ones every serialization of every engine behind the API has: a text and a boolean.
   */
  private static final Set<Class<?>> THE_API_CARRIES_IT = Set
      .of(String.class, Boolean.class, boolean.class);

  private PeaValueTypes() {
  }

  /**
   * What the engine behind the API does with that type.
   *
   * @param valueType The declared type of the value
   * @param direction Which way the value travels
   * @return The verdict for the startup check
   */
  public static ValueTypeVerdict verdictFor(
      final Class<?> valueType,
      final ValueDirection direction) {

    if (valueType == null) {
      return ValueTypeVerdict.cannotSay("no type was named");
    }
    if (valueType.isEnum()) {
      // VanillaBP hands an enum over as its name, which is a text
      return ValueTypeVerdict.survives();
    }
    if (THE_API_CARRIES_IT.contains(valueType) || CharSequence.class.isAssignableFrom(valueType)) {
      return ValueTypeVerdict.survives();
    }
    return ValueTypeVerdict
        .cannotSay(
            """
                that the Process-Engine-API names no engine and no serialization: a %s reaches \
                whichever engine is configured, and what that engine stores and hands back is \
                outside the API%s"""
                .formatted(
                    valueType.getSimpleName(),
                    direction == ValueDirection.TO_BPMS
                        ? ", the expression language reading it included"
                        : ""));

  }

}
