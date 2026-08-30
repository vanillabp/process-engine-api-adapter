package io.vanillabp.pea.deployment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.UnaryOperator;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies {@link NameClashAvoidance#USE_PREFIX} to a Process-Engine-API model. The API
 * has no BPMN model type - only opaque resources - so the adapter
 * rewrites the raw XML itself: the identifiers are read and written by LOCAL NAME,
 * independent of the BPMN dialect the underlying engine uses.
 * <p>
 * The other two modes need nothing: {@link NameClashAvoidance#NONE} scopes nothing,
 * and {@link NameClashAvoidance#BY_ADAPTER} is rejected at startup because the
 * Process-Engine-API has no isolation mechanism of its own (see
 * {@code GAPS.md}, entry 4).
 */
@Slf4j
public final class PeaScoping {

  private PeaScoping() {
  }

  /**
   * Whether the given workflow module's identifiers are prefixed for this adapter.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support (may be
   *          <code>null</code>)
   * @return Whether prefixing applies
   */
  public static boolean prefixes(
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping) {

    return (scoping != null) && (scoping.modeFor(workflowModuleId, null, adapterId) == NameClashAvoidance.USE_PREFIX);

  }

  /**
   * Rewrites the identifiers of the given BPMN resource. Returns the input unchanged
   * unless the module's mode is {@link NameClashAvoidance#USE_PREFIX}.
   *
   * @param resource The raw BPMN XML
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID of the resource
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support
   * @return The (possibly rewritten) BPMN XML
   */
  public static byte[] apply(
      final byte[] resource,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping) {

    if ((resource == null) || !prefixes(workflowModuleId, adapterId, scoping)) {
      return resource;
    }
    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // the resource is the application's own BPMN, but parsing untrusted XML
      // features is never needed here
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      final var document = factory
          .newDocumentBuilder()
          .parse(new ByteArrayInputStream(resource));

      rewriteAttribute(document, "process", "id",
          value -> scoping.scopedProcessId(workflowModuleId, value, adapterId));
      rewriteAttribute(document, "calledElement", "processId",
          value -> scoping.scopedProcessId(workflowModuleId, value, adapterId));
      rewriteAttribute(document, "message", "name",
          value -> scoping.scopedIdentifier(workflowModuleId, value, adapterId));
      rewriteAttribute(document, "signal", "name",
          value -> scoping.scopedIdentifier(workflowModuleId, value, adapterId));
      rewriteAttribute(document, "escalation", "escalationCode",
          value -> scoping.scopedIdentifier(workflowModuleId, value, adapterId));
      rewriteAttribute(document, "error", "errorCode",
          value -> scoping.scopedIdentifier(workflowModuleId, value, adapterId));
      // task definitions are scoped per PROCESS - the plain process id is the one
      // the caller passed, the model's own id may already be rewritten above
      rewriteAttribute(document, "taskDefinition", "type",
          value -> scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, value, adapterId));
      rewriteAttribute(document, "formDefinition", "externalReference",
          value -> scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, value, adapterId));

      final var output = new ByteArrayOutputStream();
      final var transformer = TransformerFactory
          .newInstance()
          .newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      transformer.transform(new DOMSource(document), new StreamResult(output));
      log.debug(
          "Process-Engine-API adapter '{}': BPMN process '{}' of workflow module '{}' is deployed "
              + "with prefixed identifiers (name-clash avoidance 'use-prefix')",
          adapterId,
          bpmnProcessId,
          workflowModuleId);
      return output.toByteArray();
    } catch (final Exception e) {
      throw new IllegalStateException(
          ("The BPMN of process '%s' (workflow module '%s') could not be rewritten for the "
              + "name-clash-avoidance mode 'use-prefix' of adapter '%s'!")
              .formatted(bpmnProcessId, workflowModuleId, adapterId), e);
    }

  }

  /**
   * Rewrites one attribute of every element of the given local name.
   */
  private static void rewriteAttribute(
      final Document document,
      final String localName,
      final String attributeName,
      final UnaryOperator<String> rewriter) {

    final var elements = document.getElementsByTagNameNS("*", localName);
    for (var index = 0; index < elements.getLength(); ++index) {
      final var element = (Element) elements.item(index);
      final var value = element.getAttribute(attributeName);
      if ((value == null) || value.isBlank()) {
        continue;
      }
      element.setAttribute(attributeName, rewriter.apply(value));
    }

  }

}
