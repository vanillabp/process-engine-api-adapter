package io.vanillabp.pea.deployment;

import java.io.ByteArrayInputStream;
import java.util.LinkedList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

/**
 * Finds the start events a BPMS would fire on its own (timer, signal, conditional) in
 * the raw BPMN. The Process-Engine-API has no BPMN model type, so the adapter reads
 * the XML itself - by LOCAL NAME, since the BPMN namespace prefix is up to whoever
 * modelled the file.
 */
public final class PeaStartEvents {

  private PeaStartEvents() {
  }

  /**
   * @param resource The raw BPMN
   * @param bpmnProcessId The process to look at
   * @return The start events the engine fires on its own, described as
   *         "&lt;id&gt; (timer)" - empty if the process is started by the
   *         application only
   */
  public static List<String> bpmsInitiatedStartEventsOf(
      final byte[] resource,
      final String bpmnProcessId) {

    final var startEvents = new LinkedList<String>();
    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // no external entities: the BPMN comes from the classpath, but parsing it
      // must not reach out anywhere
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      final var document = factory
          .newDocumentBuilder()
          .parse(new ByteArrayInputStream(resource));
      final var processes = document.getElementsByTagNameNS("*", "process");
      for (var i = 0; i < processes.getLength(); i++) {
        final var process = (Element) processes.item(i);
        if (!bpmnProcessId.equals(process.getAttribute("id"))) {
          continue;
        }
        final var elements = process.getElementsByTagNameNS("*", "startEvent");
        for (var j = 0; j < elements.getLength(); j++) {
          final var startEvent = (Element) elements.item(j);
          describe(startEvent).ifPresent(kind -> startEvents.add("'%s' (%s)".formatted(
              startEvent.getAttribute("id"),
              kind)));
        }
      }
    } catch (final Exception e) {
      // an unparsable BPMN is reported by the deployment itself - not here
      return List.of();
    }
    return startEvents;

  }

  private static java.util.Optional<String> describe(
      final Element startEvent) {

    if (startEvent.getElementsByTagNameNS("*", "timerEventDefinition").getLength() > 0) {
      return java.util.Optional.of("timer start event");
    }
    if (startEvent.getElementsByTagNameNS("*", "signalEventDefinition").getLength() > 0) {
      return java.util.Optional.of("signal start event");
    }
    if (startEvent.getElementsByTagNameNS("*", "conditionalEventDefinition").getLength() > 0) {
      return java.util.Optional.of("conditional start event");
    }
    return java.util.Optional.empty();

  }

}
