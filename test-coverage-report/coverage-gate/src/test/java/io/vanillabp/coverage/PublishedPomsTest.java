package io.vanillabp.coverage;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.PublishedPoms;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the POMs of this repository hand an application: no tool which only translates our
 * source, and no property name where a version belongs.
 * <p>
 * Lombok and the processors of Spring Boot and Quarkus are read while javac runs and have
 * nothing left to do once the class file exists. An application asked for a workflow
 * engine, not for them, and every jar it did not ask for is one more thing to scan, to
 * ship and to answer a CVE report about. The scope which says that is {@code provided}:
 * it puts the jar on our own compile path and hands it to nobody.
 * <p>
 * The second check is about the versions in those POMs. A version written as
 * {@code ${some.version}} resolves in our own build, where the POM holding the value is
 * in the reactor. It stops resolving for a consumer the day that POM stops defining the
 * property, and a timestamped snapshot cannot hold on to the parent build it went out
 * with: the consumer reads the newest build of that parent. On 2026-09-27 that turned
 * every Quarkus blueprint red while the repository which published the POM stayed green.
 * <p>
 * What the checks know sits in {@link PublishedPoms} of the platform's module
 * 'test-utils'. Every repository of VanillaBP can make these mistakes and they all make
 * them in the same way, so the rules live in one place and each repository calls them. A
 * copy per repository drifts, and the worth of these checks is that they still run in two
 * years. This test is the caller which names the file this repository publishes and the
 * tools of this build.
 * <p>
 * This repository publishes its POMs as they are, so an application reads the same file
 * a reviewer reads, and the checks read it too.
 * <p>
 * They are gates in the module which already gates this repository as a whole, and that
 * module is the last of the reactor. So they read what every other module has published
 * by then.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools of this build, each as {@code groupId:artifactId}. A tool this repository
   * starts using belongs in this list, because nothing else knows that it is one. The
   * list names more than the build uses today so that the usual next one is covered as
   * well.
   */
  private static final Set<String> TOOLS_OF_THIS_BUILD = Set
      .of(
          "org.projectlombok:lombok",
          "io.quarkus:quarkus-extension-processor",
          "org.springframework.boot:spring-boot-autoconfigure-processor",
          "org.mapstruct:mapstruct-processor",
          "io.vanillabp:vanillabp-mapstruct-fluent-accessors");

  @Test
  @DisplayName("no POM of this repository hands an application a tool of the build")
  public void noPomHandsAnApplicationAToolOfTheBuild() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
        .handAnApplicationNoToolOfTheBuild(TOOLS_OF_THIS_BUILD);

  }

  @Test
  @DisplayName("no POM of this repository hands an application a property instead of a value")
  public void noPomHandsAnApplicationAPropertyInsteadOfAValue() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
        .handAnApplicationNoPropertyInsteadOfAValue();

  }

}
