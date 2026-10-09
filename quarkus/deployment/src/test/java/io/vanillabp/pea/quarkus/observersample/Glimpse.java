package io.vanillabp.pea.quarkus.observersample;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotation of an extension which reads what this adapter's subscription delivers, the way
 * a details provider of a cockpit does.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Glimpse {

  /**
   * @return The BPMN element the method serves
   */
  String element();

}
