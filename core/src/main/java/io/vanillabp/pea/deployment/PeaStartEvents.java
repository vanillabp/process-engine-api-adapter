package io.vanillabp.pea.deployment;

import java.io.ByteArrayInputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

/**
 * Finds the start events in the raw BPMN which a BPMS fires on its own: timer, signal and
 * conditional. The Process-Engine-API has no BPMN model type, so the adapter reads the XML
 * itself. It reads by LOCAL NAME, because whoever modelled the file picks the namespace
 * prefix.
 */
public final class PeaStartEvents {

  private PeaStartEvents() {
  }

  /**
   * Reads the start events of one process out of the raw bytes. The deployment refuses the
   * module because of what this returns. This engine does not report a start it made on its
   * own. So a workflow behind such an event would begin without an aggregate, and nobody
   * would notice (GAPS entry 16).
   *
   * @param resource The raw BPMN
   * @param bpmnProcessId The process to look at
   * @return The start events the engine fires on its own, each described like
   *         "&lt;id&gt; (timer)". Empty if only the application starts the process
   */
  public static List<String> bpmsInitiatedStartEventsOf(
      final byte[] resource,
      final String bpmnProcessId) {

    final var startEvents = new LinkedList<String>();
    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // no DOCTYPE and so no external entities: the BPMN comes from the classpath, but
      // reading it must not reach out anywhere
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
      // the deployment itself reports a BPMN it cannot read, so nothing is said here
      return List.of();
    }
    return startEvents;

  }

  private static Optional<String> describe(
      final Element startEvent) {

    if (startEvent.getElementsByTagNameNS("*", "timerEventDefinition").getLength() > 0) {
      return Optional.of("timer start event");
    }
    if (startEvent.getElementsByTagNameNS("*", "signalEventDefinition").getLength() > 0) {
      return Optional.of("signal start event");
    }
    if (startEvent.getElementsByTagNameNS("*", "conditionalEventDefinition").getLength() > 0) {
      return Optional.of("conditional start event");
    }
    return Optional.empty();

  }

}
