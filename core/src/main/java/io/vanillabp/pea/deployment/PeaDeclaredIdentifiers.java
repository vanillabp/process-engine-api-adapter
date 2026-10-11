package io.vanillabp.pea.deployment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.pea.PeaBpmnModel;
import lombok.extern.slf4j.Slf4j;

/**
 * The identifiers a BPMN model of this adapter declares, as the application wrote them. The
 * core compares them with the other workflow modules of the same application. It needs no
 * engine for that, because the names are in the models the adapter reads anyway.
 * <p>
 * The element and attribute names are kept in ONE list. {@link PeaScoping} rewrites what it
 * names, and this class reads it. Neither of them sees a dialect whose element names are not
 * in that list. So the core gets what the rewrite knows and nothing more. The API has no BPMN
 * model type to ask instead (see {@code GAPS.md}, entry 1).
 * <p>
 * A decision id is not in the list. This adapter never scopes one, because the API has no
 * link from a business rule task to a decision which could be renamed along with it (see
 * {@code GAPS.md}, entry 22). The scoped form the core would compose is then not what the
 * engine sees, and a warning would name a name nobody deploys.
 */
@Slf4j
public final class PeaDeclaredIdentifiers {

  private PeaDeclaredIdentifiers() {
  }

  /**
   * One attribute of one element, and which kind of identifier it holds.
   *
   * @param localName The local name of the element, so the namespace prefix of the
   *          dialect does not matter
   * @param attributeName The attribute's local name
   * @param kind What the core scopes this identifier as
   */
  record DeclaringElement(String localName,
                          String attributeName,
                          ScopedIdentifierKind kind) {
  }

  /**
   * What a workflow module scopes on its own, without a BPMN process taking part. These
   * elements sit in the <code>definitions</code> element and not inside a process. So a file
   * with two processes declares each of them once for both.
   */
  static final List<DeclaringElement> ELEMENTS_SCOPED_BY_THE_WORKFLOW_MODULE = List
      .of(
          new DeclaringElement("message", "name", ScopedIdentifierKind.MESSAGE_NAME),
          new DeclaringElement("signal", "name", ScopedIdentifierKind.SIGNAL_NAME),
          new DeclaringElement("escalation", "escalationCode", ScopedIdentifierKind.ESCALATION_CODE),
          new DeclaringElement("error", "errorCode", ScopedIdentifierKind.ERROR_CODE));

  /**
   * Reads the identifiers of one model, with the PLAIN names the application gave them.
   * Pass the model as it was read, not the resource on its way to the engine, because that
   * one may already carry the prefix.
   * <p>
   * This is only a check. So a file which cannot be read gives a debug line and an empty
   * answer, and the deployment goes on. {@code deployResources} deploys the same file
   * anyway, and the engine is the one to say what is wrong with it.
   *
   * @param model The model as it was read from the BPMN file
   * @return What it declares, plain, possibly with duplicates across the models of one
   *         file
   */
  public static Collection<ModelIdentifier> of(
      final PeaBpmnModel model) {

    if (model == null) {
      return List.of();
    }
    final var declared = new ArrayList<ModelIdentifier>();
    // the task definition of a service-like task and the external form reference of a
    // user task. Both were read per process when the file was parsed, and they are scoped
    // per process too
    taskDefinitionsOf(model.tasks(), model.bpmnProcessId(), declared);
    taskDefinitionsOf(model.userTasks(), model.bpmnProcessId(), declared);
    try {
      readElementsScopedByTheWorkflowModule(model.resource(), declared);
    } catch (final Exception e) {
      log.debug(
          "The identifiers declared by BPMN file '{}' could not be read - they are not checked "
              + "against the other workflow modules",
          model.filename(),
          e);
    }
    return declared;

  }

  /**
   * Skips a task without a task definition. It has no name to scope, and the wiring
   * validation reports it.
   */
  private static void taskDefinitionsOf(
      final List<BpmnTaskSpec> tasks,
      final String bpmnProcessId,
      final Collection<ModelIdentifier> declared) {

    if (tasks == null) {
      return;
    }
    tasks
        .stream()
        .map(BpmnTaskSpec::taskDefinition)
        .filter(taskDefinition -> (taskDefinition != null) && !taskDefinition.isBlank())
        .forEach(
            taskDefinition -> declared
                .add(new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, taskDefinition, bpmnProcessId)));

  }

  private static void readElementsScopedByTheWorkflowModule(
      final byte[] resource,
      final Collection<ModelIdentifier> declared) throws XMLStreamException, IOException {

    if (resource == null) {
      return;
    }
    final var factory = XMLInputFactory.newFactory();
    // no external entities and no DTDs, against XXE attacks
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

    XMLStreamReader reader = null;
    try (var in = new ByteArrayInputStream(resource)) {
      reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        if (reader.next() != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        for (final var element : ELEMENTS_SCOPED_BY_THE_WORKFLOW_MODULE) {
          if (!element.localName().equals(reader.getLocalName())) {
            continue;
          }
          final var plainIdentifier = attributeOf(reader, element.attributeName());
          if (plainIdentifier != null) {
            declared.add(new ModelIdentifier(element.kind(), plainIdentifier, null));
          }
        }
      }
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (final XMLStreamException e) {
          // the reader works on bytes in memory, so a failed close loses nothing
        }
      }
    }

  }

  /**
   * The attribute with the given local name, in whatever namespace. An identifier is spelled
   * the same in every dialect this adapter knows, but its namespace prefix is not. An
   * attribute a modeller left empty is nothing to scope, so it is nothing to report.
   *
   * @param reader The reader, standing on a start element
   * @param attributeName The attribute's local name
   * @return Its value, or <code>null</code> where there is nothing in it
   */
  private static String attributeOf(
      final XMLStreamReader reader,
      final String attributeName) {

    for (var index = 0; index < reader.getAttributeCount(); ++index) {
      if (!attributeName.equals(reader.getAttributeLocalName(index))) {
        continue;
      }
      final var value = reader.getAttributeValue(index);
      return (value == null) || value.isBlank()
          ? null
          : value;
    }
    return null;

  }

}
