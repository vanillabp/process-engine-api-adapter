package io.vanillabp.pea;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import dev.bpmcrafters.processengineapi.Empty;
import dev.bpmcrafters.processengineapi.MetaInfo;
import dev.bpmcrafters.processengineapi.MetaInfoAware;
import dev.bpmcrafters.processengineapi.deploy.DeployBundleCommand;
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.deploy.DeploymentInformation;
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd;
import dev.bpmcrafters.processengineapi.task.TaskSubscription;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * Unit tests of {@link PeaDeploymentService}: the StAX-based BPMN parsing of
 * {@code readBpmn} (executable-process id extraction) and {@code deployResources} deploying
 * the module's resources through the (mock) Process-Engine-API {@code DeploymentApi}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaDeploymentServiceTest {

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final PeaDeploymentService service = new PeaDeploymentService(
      "pea", engine, TestCollaborators.of(new PermissiveInvoker()), engine, engine);

  /**
   * The service under test with a core which avoids name clashes the given way - what it
   * deploys under which name is decided while it is built, not afterwards.
   */
  private PeaDeploymentService serviceScopedBy(
      final NameClashAvoidanceSupport scoping) {

    return new PeaDeploymentService(
        "pea", engine, TestCollaborators.of(new PermissiveInvoker(), scoping), engine, engine);

  }

  private static final String DECISION_TABLE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
          id="creditRatingDefinitions" name="Credit rating" namespace="http://vanillabp.io/test">
        <decision id="creditRating" name="Credit rating">
          <decisionTable id="creditRatingTable" hitPolicy="UNIQUE"/>
        </decision>
      </definitions>
      """;

  private static ByteArrayInputStream bpmn(
      final String xml) {

    return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));

  }

  @Test
  public void readBpmnExtractsSingleExecutableProcessId() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <bpmn:process id="OnlyProcess" isExecutable="true"/>
        </bpmn:definitions>
        """;

    final var models = service.readBpmn("mod", "one.bpmn", bpmn(xml), true);

    Assertions.assertEquals(1, models.size());
    Assertions.assertEquals("OnlyProcess", models.get(0).getKey());
    final var model = models.get(0).getValue();
    Assertions.assertEquals("OnlyProcess", model.bpmnProcessId());
    Assertions.assertEquals("one.bpmn", model.filename());
    Assertions.assertTrue(new String(model.resource(), StandardCharsets.UTF_8).contains("OnlyProcess"));

  }

  @Test
  public void readBpmnReturnsOnlyExecutableProcesses() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <bpmn:process id="Executable1" isExecutable="true"/>
          <bpmn:process id="NonExecutable" isExecutable="false"/>
          <bpmn:process id="NoFlag"/>
          <bpmn:process id="Executable2" isExecutable="true"/>
        </bpmn:definitions>
        """;

    final var models = service.readBpmn("mod", "several.bpmn", bpmn(xml), true);

    Assertions.assertEquals(2, models.size());
    Assertions.assertEquals("Executable1", models.get(0).getKey());
    Assertions.assertEquals("Executable2", models.get(1).getKey());

  }

  @Test
  public void readBpmnWithoutExecutableProcessReturnsEmpty() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <bpmn:process id="Nope" isExecutable="false"/>
        </bpmn:definitions>
        """;

    Assertions.assertTrue(service.readBpmn("mod", "none.bpmn", bpmn(xml), true).isEmpty());

  }

  @Test
  public void readBpmnWrapsParseErrorsInBpmnParseException() {

    final var broken = "<bpmn:definitions><unclosed>";

    Assertions.assertThrows(
        BpmnParseException.class,
        () -> service.readBpmn("mod", "broken.bpmn", bpmn(broken), true));

  }

  @Test
  public void deployResourcesDeploysEachFileOnceThroughTheDeploymentApi() {

    final var context = new PeaProcessingContext("mod");
    // two executable processes in the same file must be deployed as ONE resource...
    context.getModels()
        .add(new PeaBpmnModel("a.bpmn", "a-bytes".getBytes(StandardCharsets.UTF_8), "P1", List.of()));
    context.getModels()
        .add(new PeaBpmnModel("a.bpmn", "a-bytes".getBytes(StandardCharsets.UTF_8), "P2", List.of()));
    // ...and a second file as another resource
    context.getModels()
        .add(new PeaBpmnModel("b.bpmn", "b-bytes".getBytes(StandardCharsets.UTF_8), "P3", List.of()));

    service.deployResources("mod", context);

    Assertions.assertEquals(1, engine.getDeployments().size(), "exactly one deployment bundle expected");
    final var deployment = engine.getDeployments().get(0);
    Assertions.assertEquals(2, deployment.resources().size(), "each BPMN file deployed exactly once");
    Assertions.assertTrue(
        deployment.resources().stream().anyMatch(resource -> "a.bpmn".equals(resource.getName())));
    Assertions.assertTrue(
        deployment.resources().stream().anyMatch(resource -> "b.bpmn".equals(resource.getName())));
    Assertions.assertNull(deployment.tenantId(), "module-as-tenant is not expressible - deployed to default tenant");

  }

  @Test
  @DisplayName("A decision table of the module is one more resource of the same bundle")
  public void decisionTablesAreDeployedWithTheProcesses() {

    final var context = new PeaProcessingContext("mod");
    context
        .getModels()
        .add(new PeaBpmnModel("a.bpmn", "a-bytes".getBytes(StandardCharsets.UTF_8), "P1", List.of()));
    service.readDmn("mod", context, "rating.dmn", bpmn(DECISION_TABLE));

    service.deployResources("mod", context);

    final var deployment = engine.getDeployments().get(0);
    Assertions
        .assertEquals(2, deployment.resources().size(), "the process and the decision travel together");
    Assertions
        .assertTrue(deployment.resources().stream().anyMatch(resource -> "rating.dmn".equals(resource.getName())));

  }

  @Test
  @DisplayName("A business rule task calling a decision expects no @WorkflowTask method")
  public void aBusinessRuleTaskCallingADecisionIsNoTaskToWire() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:process id="DecidingProcess" isExecutable="true">
            <bpmn:businessRuleTask id="rate">
              <bpmn:extensionElements>
                <zeebe:calledDecision decisionId="creditRating" resultVariable="rating" />
              </bpmn:extensionElements>
            </bpmn:businessRuleTask>
            <bpmn:businessRuleTask id="workerRule">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="rateIt" />
              </bpmn:extensionElements>
            </bpmn:businessRuleTask>
          </bpmn:process>
        </bpmn:definitions>
        """;

    final var models = service.readBpmn("mod", "deciding.bpmn", bpmn(xml), true);

    final var tasks = models.get(0).getValue().tasks();
    Assertions
        .assertEquals(
            List.of("workerRule"),
            tasks.stream().map(BpmnTaskSpec::activityId).toList(),
            "the engine evaluates the decision; only the task wired by a task definition is the application's");

  }

  @Test
  public void deployResourcesWithoutModelsDeploysNothing() {

    service.deployResources("mod", null);
    service.deployResources("mod", new PeaProcessingContext("mod"));

    Assertions.assertTrue(engine.getDeployments().isEmpty());

  }


  @Test
  public void readBpmnExtractsTaskDefinitionsIncludingUndefinedOnes() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:process id="TaskedProcess" isExecutable="true">
            <bpmn:serviceTask id="t1">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="doIt" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
            <bpmn:sendTask id="t2">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="sendIt" />
              </bpmn:extensionElements>
            </bpmn:sendTask>
            <bpmn:serviceTask id="t3" />
          </bpmn:process>
        </bpmn:definitions>
        """;

    final var models = service.readBpmn("mod", "tasks.bpmn", bpmn(xml), true);

    Assertions.assertEquals(1, models.size());
    final var tasks = models.get(0).getValue().tasks();
    Assertions.assertEquals(3, tasks.size());
    Assertions.assertEquals("doIt", tasks.get(0).taskDefinition());
    Assertions.assertEquals("t1", tasks.get(0).activityId());
    Assertions.assertEquals("sendIt", tasks.get(1).taskDefinition());
    // a service-like task WITHOUT a task definition yields a null-definition spec
    // (the wiring validation reports it with a guiding message)
    Assertions.assertEquals("t3", tasks.get(2).activityId());
    Assertions.assertNull(tasks.get(2).taskDefinition());

  }

  @Test
  public void readBpmnCarriesTheUserTaskNameTheModellerWrote() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:process id="NamedProcess" isExecutable="true">
            <bpmn:userTask id="ut1" name="Approve the order">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="approve" />
              </bpmn:extensionElements>
            </bpmn:userTask>
            <bpmn:userTask id="ut2">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="check" />
              </bpmn:extensionElements>
            </bpmn:userTask>
            <bpmn:userTask id="ut3" name="  ">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="blank" />
              </bpmn:extensionElements>
            </bpmn:userTask>
          </bpmn:process>
        </bpmn:definitions>
        """;

    final var userTasks = service
        .readBpmn("mod", "named.bpmn", bpmn(xml), true)
        .get(0)
        .getValue()
        .userTasks();

    Assertions.assertEquals(3, userTasks.size());
    // what a task list shows: this engine reports no name of its own, so the model is
    // where it comes from
    Assertions.assertEquals("approve", userTasks.get(0).taskDefinition());
    Assertions.assertEquals("Approve the order", userTasks.get(0).name());
    // a user task the modeller did not name has none, and neither has one named with
    // nothing but whitespace - an absent attribute and an empty one are the same thing
    Assertions.assertEquals("check", userTasks.get(1).taskDefinition());
    Assertions.assertNull(userTasks.get(1).name());
    Assertions.assertNull(userTasks.get(2).name());

  }

  @Test
  public void startWorkflowProcessingSubscribesPerDistinctTaskDefinitionAndStopUnsubscribes() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:process id="P1" isExecutable="true">
            <bpmn:serviceTask id="a">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="shared" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
          </bpmn:process>
          <bpmn:process id="P2" isExecutable="true">
            <bpmn:serviceTask id="b">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="shared" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
            <bpmn:serviceTask id="c">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="own" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
          </bpmn:process>
        </bpmn:definitions>
        """;

    PeaProcessingContext context = null;
    for (final var entry : service.readBpmn("mod", "two.bpmn", bpmn(xml), true)) {
      context = service.prepareBpmn("mod", context, "two.bpmn", entry.getKey(), entry.getValue());
    }

    service.startWorkflowProcessing("mod", context);

    // 'shared' is used by both processes but subscribed ONCE
    Assertions.assertEquals(2, engine.getSubscriptions().size());
    Assertions.assertEquals(
        List.of("shared", "own"),
        engine
            .getSubscriptions()
            .stream()
            .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
            .toList());

    service.stopWorkflowProcessing("mod", context);
    Assertions.assertTrue(engine.getSubscriptions().isEmpty());

  }

  @Test
  public void lifecycleMethodsTolerateAModuleWithoutModels() {

    service.startWorkflowProcessing("mod", null);
    service.stopWorkflowProcessing("mod", null);
    Assertions.assertTrue(engine.getSubscriptions().isEmpty());

  }

  @Test
  public void adapterMetadataIsExposed() {

    Assertions.assertEquals("pea", service.getAdapterId());
    Assertions.assertEquals("process-engine-api", service.getAdapterType());
    Assertions.assertEquals(PeaBpmnModel.class, service.getModelType());
    Assertions.assertEquals(PeaProcessingContext.class, service.getProcessContextType());

  }

  @Test
  public void readBpmnWrapsIoErrorsInBpmnParseException() {

    final var failing = new InputStream() {

      @Override
      public int read() throws IOException {
        throw new IOException("boom");
      }

    };

    Assertions.assertThrows(
        BpmnParseException.class,
        () -> service.readBpmn("mod", "broken.bpmn", failing, true));

  }

  @Test
  public void failingDeploymentYieldsGuidingIllegalState() {

    final var failingDeploy = new DeploymentApi() {

      @Override
      public CompletableFuture<DeploymentInformation> deploy(
          final DeployBundleCommand command) {
        return CompletableFuture.failedFuture(new IllegalStateException("engine down"));
      }

      @Override
      public MetaInfo meta(
          final MetaInfoAware instance) {
        return new MetaInfo() {
        };
      }

    };
    final var failingService = new PeaDeploymentService(
        "pea", failingDeploy, TestCollaborators.of(new PermissiveInvoker()), engine, engine);

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <bpmn:process id="OnlyProcess" isExecutable="true"/>
        </bpmn:definitions>
        """;
    PeaProcessingContext context = null;
    for (final var entry : failingService.readBpmn("mod", "one.bpmn", bpmn(xml), true)) {
      context = failingService.prepareBpmn("mod", context, "one.bpmn", entry.getKey(), entry.getValue());
    }
    final var finalContext = context;

    final var failure = Assertions.assertThrows(
        IllegalStateException.class,
        () -> failingService.deployResources("mod", finalContext));
    Assertions.assertTrue(failure.getMessage().contains("mod"));

  }

  @Test
  public void failingSubscriptionYieldsGuidingIllegalState() {

    final var failingSubscribe = new TaskSubscriptionApi() {

      @Override
      public CompletableFuture<TaskSubscription> subscribeForTask(
          final SubscribeForTaskCmd cmd) {
        return CompletableFuture.failedFuture(new IllegalStateException("engine down"));
      }

      @Override
      public CompletableFuture<Empty> unsubscribe(
          final UnsubscribeFromTaskCmd cmd) {
        return CompletableFuture.failedFuture(new IllegalStateException("engine down"));
      }

      @Override
      public MetaInfo meta(
          final MetaInfoAware instance) {
        return new MetaInfo() {
        };
      }

      @Override
      public Set<String> getSupportedRestrictions() {
        return Set.of();
      }

    };
    final var failingService = new PeaDeploymentService(
        "pea", engine, TestCollaborators.of(new PermissiveInvoker()), failingSubscribe, engine);

    final var context = contextWithOneTask(failingService);

    final var failure = Assertions.assertThrows(
        IllegalStateException.class,
        () -> failingService.startWorkflowProcessing("mod", context));
    Assertions.assertTrue(
        failure.getMessage().contains("doIt"),
        "expected the failing task definition to be named but got: "
            + failure.getMessage());

    // failing USER-task subscriptions are equally guiding
    final var userTaskXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:process id="UTProcess" isExecutable="true">
            <bpmn:userTask id="ut1">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="utApprove" />
              </bpmn:extensionElements>
            </bpmn:userTask>
          </bpmn:process>
        </bpmn:definitions>
        """;
    PeaProcessingContext userTaskContext = null;
    for (final var entry : failingService.readBpmn("mod", "ut.bpmn", bpmn(userTaskXml), true)) {
      userTaskContext = failingService.prepareBpmn("mod", userTaskContext, "ut.bpmn", entry.getKey(), entry.getValue());
    }
    final var finalUserTaskContext = userTaskContext;
    final var userTaskFailure = Assertions.assertThrows(
        IllegalStateException.class,
        () -> failingService.startWorkflowProcessing("mod", finalUserTaskContext));
    Assertions.assertTrue(
        userTaskFailure.getMessage().contains("utApprove"),
        "expected the failing form reference to be named but got: "
            + userTaskFailure.getMessage());

    // a failing UNsubscribe on stop is only logged (graceful shutdown)
    context
        .getSubscriptions()
        .add(new TaskSubscription() {
        });
    Assertions.assertDoesNotThrow(() -> failingService.stopWorkflowProcessing("mod", context));

  }

  private PeaProcessingContext contextWithOneTask(
      final PeaDeploymentService target) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:process id="TaskedProcess" isExecutable="true">
            <bpmn:serviceTask id="t1">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="doIt" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
          </bpmn:process>
        </bpmn:definitions>
        """;
    PeaProcessingContext context = null;
    for (final var entry : target.readBpmn("mod", "one.bpmn", bpmn(xml), true)) {
      context = target.prepareBpmn("mod", context, "one.bpmn", entry.getKey(), entry.getValue());
    }
    return context;

  }

  /**
   * Plays both halves of the split task SPI: the deployment service wires through
   * {@code WorkflowTaskWiring} and opens its task subscriptions with
   * {@code WorkflowTaskInvoker}, so a double standing in for the core answers both.
   */
  static class PermissiveInvoker implements WorkflowTaskWiring, WorkflowTaskInvoker {

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<BpmnTaskSpec> tasks) {
    }

    @Override
    public void validateNoUnwiredWorkflowTaskMethods(
        final String workflowModuleId) {
    }

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context) {
      throw new UnsupportedOperationException();
    }

    // The migration fallback, deprecated for removal in 2.1 and none of this
    // BPMS's business: a test double implements it as long as the interface declares
    // it, and the mandatory 'removal' lint needs the suppression
    @SuppressWarnings("removal")
    @Override
    public boolean workflowAggregateHasProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String propertyName) {
      return false;
    }

    @SuppressWarnings("removal")
    @Override
    public Object resolveWorkflowAggregateProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String propertyName) {
      return null;
    }

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {
      return true;
    }


    @Override
    public Map<String, Object> syncedWorkflowAggregateValues(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final AggregateSyncMode adapterDefault) {

      return Map.of();

    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {
      return "id";
    }

  }


  @Test
  @DisplayName("Two adapter ids of this type cannot address different engines - the boot fails guiding")
  public void twoAdapterIdsOfThisTypeAreRejected() {

    // The Process-Engine-API is provided by the application as beans and
    // carries no per-adapter-id connection configuration (GAPS.md, entry 14)
    final var exception = Assertions.assertThrows(
        IllegalStateException.class,
        () -> service.validateDistinctAdapterInstances(List.of("pea-old", "pea-new")));

    Assertions.assertTrue(exception.getMessage().contains("pea-old"), exception::getMessage);
    Assertions.assertTrue(exception.getMessage().contains("pea-new"), exception::getMessage);
    Assertions.assertTrue(exception.getMessage().contains("process-engine-api"), exception::getMessage);

    // a single id is the normal case and never complains
    Assertions.assertDoesNotThrow(() -> service.validateDistinctAdapterInstances(List.of("pea")));
    Assertions.assertDoesNotThrow(() -> service.validateDistinctAdapterInstances(null));

  }


  /**
   * A core which avoids name clashes the given way for workflow module
   * {@code loan-approval} - the real service rather than a double, because the form an
   * identifier reaches the engine in is what the core composes.
   */
  private static NameClashAvoidanceService scopingWith(
      final NameClashAvoidance mode) {

    return scopingWith(mode, "loan-approval");

  }

  /**
   * The same core for the given workflow modules, which is what a clash between two of
   * them needs.
   *
   * @param mode The mode every one of them resolves
   * @param workflowModuleIds The modules the application configures
   */
  private static NameClashAvoidanceService scopingWith(
      final NameClashAvoidance mode,
      final String... workflowModuleIds) {

    final var adapter = AdapterConfigProperties
        .ofType("process-engine-api");
    adapter.setNameClashAvoidance(mode);
    final var workflowModules = new LinkedHashMap<String, WorkflowModuleAdapterProperties>();
    for (final var workflowModuleId : workflowModuleIds) {
      workflowModules.put(workflowModuleId, new WorkflowModuleAdapterProperties());
    }
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("pea", adapter))
        .prioritizedAdapters(List.of("pea"))
        .workflowModules(workflowModules)
        .build();
    properties.validateAndLink();
    return new NameClashAvoidanceService(properties);

  }

  /**
   * This BPMS has no isolation mechanism of its own, so the DEFAULT mode
   * {@code by-adapter} cannot be served - and {@code use-prefix} rewrites the raw
   * BPMN (the API has no model type).
   */
  @Nested
  class NameClashAvoidanceModes {

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"         xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:message id="Msg" name="PaymentReceived"/>
          <bpmn:error id="Err" errorCode="PAYMENT_FAILED"/>
          <bpmn:process id="RiskAssessment" isExecutable="true">
            <bpmn:serviceTask id="Task">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="scoreApplicant"/>
              </bpmn:extensionElements>
            </bpmn:serviceTask>
          </bpmn:process>
        </bpmn:definitions>
        """;

    @Test
    public void byAdapterIsRejectedWhileDeploying() {

      final var service = serviceScopedBy(
          scopingWith(NameClashAvoidance.BY_ADAPTER));

      final var context = service.prepareBpmn(
          "loan-approval", null, "risk.bpmn", "RiskAssessment", new PeaBpmnModel(
              "risk.bpmn", XML.getBytes(StandardCharsets.UTF_8), "RiskAssessment", List.of()));

      final var exception = Assertions.assertThrows(
          IllegalStateException.class,
          () -> service.deployResources("loan-approval", context));
      Assertions.assertTrue(
          exception.getMessage().contains("no isolation mechanism of its own"), exception::getMessage);
      Assertions.assertTrue(exception.getMessage().contains("'loan-approval'"), exception::getMessage);
      Assertions.assertTrue(exception.getMessage().contains("use-prefix"), exception::getMessage);
      Assertions.assertTrue(exception.getMessage().contains("none"), exception::getMessage);

    }

    @Test
    @DisplayName("A decision id is deployed as it was modelled - this API has no reference to rename with it")
    public void aDecisionIdIsNotScoped() {

      final var context = new PeaProcessingContext("mod");
      serviceScopedBy(scopingWith(NameClashAvoidance.USE_PREFIX)).readDmn("mod", context, "rating.dmn",
          bpmn(DECISION_TABLE));

      Assertions
          .assertEquals(
              List.of("creditRating"),
              List
                  .copyOf(
                      io.vanillabp.integration.adapter.spi.DmnDecisionIds
                          .of(context.getDecisions().get("rating.dmn"))),
              "renaming the decision while no business rule task can follow would break every model (GAPS 22)");

    }

    @Test
    public void usePrefixRewritesTheDeployedBpmn() {

      final var service = serviceScopedBy(
          scopingWith(NameClashAvoidance.USE_PREFIX));

      final var context = service.prepareBpmn(
          "loan-approval", null, "risk.bpmn", "RiskAssessment", new PeaBpmnModel(
              "risk.bpmn", XML.getBytes(StandardCharsets.UTF_8), "RiskAssessment", List.of()));

      // the record keeps the PLAIN identifiers - they key the core's registries ...
      Assertions.assertEquals("RiskAssessment", context.getModels().getFirst().bpmnProcessId());
      // ... while the deployed BYTES carry the scoped ones
      // (see decision 2 in the repository's DECISIONS.md)
      final var deployed = new String(
          context.getModels().getFirst().resource(), StandardCharsets.UTF_8);
      Assertions.assertTrue(deployed.contains("id=\"loan-approval__RiskAssessment\""), deployed);
      Assertions.assertTrue(deployed.contains("name=\"loan-approval__PaymentReceived\""), deployed);
      Assertions.assertTrue(deployed.contains("errorCode=\"loan-approval__PAYMENT_FAILED\""), deployed);
      Assertions.assertTrue(
          deployed.contains("type=\"loan-approval__RiskAssessment__scoreApplicant\""), deployed);

      // deploying works (no isolation complaint in this mode)
      service.deployResources("loan-approval", context);
      Assertions.assertEquals(1, engine.getDeployments().size());

    }

    @Test
    public void noneChangesNothing() {

      final var service = serviceScopedBy(scopingWith(NameClashAvoidance.NONE));

      final var context = service.prepareBpmn(
          "loan-approval", null, "risk.bpmn", "RiskAssessment", new PeaBpmnModel(
              "risk.bpmn", XML.getBytes(StandardCharsets.UTF_8), "RiskAssessment", List.of()));

      Assertions.assertEquals(
          XML,
          new String(context.getModels().getFirst().resource(), StandardCharsets.UTF_8));
      service.deployResources("loan-approval", context);

    }

  }

  /**
   * The only name clash this adapter can look for: what the models of a workflow module
   * declare. The engine behind the API cannot be asked which identifiers it already holds
   * (see {@code GAPS.md}, entry 24), while the names of the models being deployed are read
   * anyway - so the core is told about those and about nothing else.
   */
  @Nested
  class IdentifiersTheModelsDeclare {

    private static final String XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:message id="Msg" name="PaymentReceived"/>
          <bpmn:signal id="Sig" name="RatingDone"/>
          <bpmn:error id="Err" errorCode="PAYMENT_FAILED"/>
          <bpmn:escalation id="Esc" escalationCode="MANUAL_REVIEW"/>
          <bpmn:process id="RiskAssessment" isExecutable="true">
            <bpmn:serviceTask id="Task">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="scoreApplicant"/>
              </bpmn:extensionElements>
            </bpmn:serviceTask>
            <bpmn:userTask id="Review">
              <bpmn:extensionElements>
                <zeebe:formDefinition externalReference="reviewForm"/>
              </bpmn:extensionElements>
            </bpmn:userTask>
          </bpmn:process>
        </bpmn:definitions>
        """;

    /**
     * A BPMN written for an engine whose task definition is a Camunda 7 external task
     * topic - an element name the adapter's rewrite does not know.
     */
    private static final String XML_OF_AN_UNKNOWN_DIALECT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
          <bpmn:message id="Msg" name="PaymentReceived"/>
          <bpmn:process id="RiskAssessment" isExecutable="true">
            <bpmn:serviceTask id="Task" camunda:type="external" camunda:topic="scoreApplicant"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private final List<ModelIdentifier> reported = new ArrayList<>();

    /**
     * A core which records what it is told instead of wording its warning - what the
     * warning says is the core's own business, what arrives here is this adapter's.
     */
    private NameClashAvoidanceSupport recordingScoping(
        final NameClashAvoidance mode) {

      final var scoping = Mockito.spy(scopingWith(mode));
      Mockito
          .doAnswer(invocation -> {
            reported.addAll(invocation.<Collection<ModelIdentifier>>getArgument(2));
            return null;
          })
          .when(scoping)
          .reportIdentifiersTheModelsDeclare(Mockito.any(), Mockito.any(), Mockito.any());
      return scoping;

    }

    /**
     * Runs the deployment pipeline of one BPMN file for workflow module
     * {@code loan-approval}.
     */
    private void deploy(
        final PeaDeploymentService service,
        final String xml) {

      PeaProcessingContext context = null;
      for (final var model : service.readBpmn("loan-approval", "risk.bpmn", bpmn(xml), true)) {
        context = service.prepareBpmn("loan-approval", context, "risk.bpmn", model.getKey(), model.getValue());
      }
      service.deployResources("loan-approval", context);

    }

    @Test
    @DisplayName("Every identifier the rewrite knows reaches the core with the name the application wrote")
    public void identifiersArrivePlainAndByTheirKind() {

      // the mode which lets two workflow modules share a name, and therefore the one the
      // check exists for
      deploy(serviceScopedBy(recordingScoping(NameClashAvoidance.NONE)), XML);

      Assertions
          .assertEquals(
              Set
                  .of(
                      new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "PaymentReceived", null),
                      new ModelIdentifier(ScopedIdentifierKind.SIGNAL_NAME, "RatingDone", null),
                      new ModelIdentifier(ScopedIdentifierKind.ERROR_CODE, "PAYMENT_FAILED", null),
                      new ModelIdentifier(ScopedIdentifierKind.ESCALATION_CODE, "MANUAL_REVIEW", null),
                      new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "scoreApplicant", "RiskAssessment"),
                      new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "reviewForm", "RiskAssessment")),
              Set.copyOf(reported));

    }

    @Test
    @DisplayName("A task definition names its BPMN process, because that is what it is scoped by")
    public void aTaskDefinitionArrivesWithItsBpmnProcessId() {

      // under 'use-prefix' the deployed bytes carry the prefix, so this also shows that
      // what the core is told are the plain names
      deploy(serviceScopedBy(recordingScoping(NameClashAvoidance.USE_PREFIX)), XML);

      Assertions
          .assertEquals(
              List
                  .of(
                      new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "scoreApplicant", "RiskAssessment"),
                      new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "reviewForm", "RiskAssessment")),
              reported
                  .stream()
                  .filter(identifier -> identifier.kind() == ScopedIdentifierKind.TASK_DEFINITION)
                  .toList());
      Assertions
          .assertTrue(
              reported.contains(new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "PaymentReceived", null)),
              reported::toString);

    }

    @Test
    @DisplayName("An element name the rewrite does not know is not reported as something else")
    public void anUnknownElementNameIsNotReported() {

      deploy(serviceScopedBy(recordingScoping(NameClashAvoidance.NONE)), XML_OF_AN_UNKNOWN_DIALECT);

      // the message name is BPMN itself and is found; the external task topic is not a
      // task definition this adapter scopes, so reporting it would name a name the engine
      // never sees under that kind
      Assertions
          .assertEquals(
              List.of(new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "PaymentReceived", null)),
              reported);

    }

    @Test
    @DisplayName("A workflow module which deploys nothing has nothing to report")
    public void aModuleWithoutModelsReportsNothing() {

      final var scoping = recordingScoping(NameClashAvoidance.NONE);
      final var service = serviceScopedBy(scoping);

      service.deployResources("loan-approval", new PeaProcessingContext("loan-approval"));
      service.deployResources("loan-approval", null);

      Mockito
          .verify(scoping, Mockito.never())
          .reportIdentifiersTheModelsDeclare(Mockito.any(), Mockito.any(), Mockito.any());

    }

    @Test
    @DisplayName("A file which cannot be read costs the deployment nothing")
    public void anUnreadableFileIsNotReportedAndDeploysAnyway() {

      final var service = serviceScopedBy(recordingScoping(NameClashAvoidance.NONE));

      final var context = service
          .prepareBpmn(
              "loan-approval",
              null,
              "broken.bpmn",
              "RiskAssessment",
              new PeaBpmnModel(
                  "broken.bpmn", "not XML at all".getBytes(StandardCharsets.UTF_8), "RiskAssessment", List.of()));
      service.deployResources("loan-approval", context);

      Assertions.assertTrue(reported.isEmpty(), reported::toString);
      Assertions.assertEquals(1, engine.getDeployments().size());

    }

    @Test
    @DisplayName("A check which fails does not fail the deployment the engine accepted")
    public void aFailingCheckLeavesTheDeploymentAlone() {

      final var scoping = Mockito.spy(scopingWith(NameClashAvoidance.NONE));
      Mockito
          .doThrow(new IllegalStateException("the check itself broke"))
          .when(scoping)
          .reportIdentifiersTheModelsDeclare(Mockito.any(), Mockito.any(), Mockito.any());
      final var service = serviceScopedBy(scoping);

      Assertions.assertDoesNotThrow(() -> deploy(service, XML));
      Assertions.assertEquals(1, engine.getDeployments().size());

    }

  }

  /**
   * Two BPMN processes which reach the one engine behind this API under the same id. The
   * engine keeps no workflow module apart from another (see {@code GAPS.md}, entry 15), so a
   * process id an earlier workflow module took is taken for the next one as well, and the
   * boot ends rather than letting the engine keep one of the two models and lose the other.
   * <p>
   * This adapter hands over the module it is deploying and nothing more. What spans the
   * modules is the core, which keeps which module reached the BPMS under which identifier,
   * so every test below deploys two workflow modules for real instead of handing one call a
   * wider collection.
   */
  @Nested
  class CollidingProcessIds {

    private static final String PROCESS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <bpmn:process id="%s" isExecutable="true"/>
        </bpmn:definitions>
        """;

    /**
     * Runs the deployment pipeline for the given workflow module, one BPMN file per process
     * id. All of them travel in one deployment, and each file gets a name of its own, so a
     * module may declare the same process id twice.
     *
     * @param service The service under test
     * @param workflowModuleId The workflow module
     * @param bpmnProcessIds The plain BPMN process ids its files declare
     */
    private void deploy(
        final PeaDeploymentService service,
        final String workflowModuleId,
        final String... bpmnProcessIds) {

      PeaProcessingContext context = null;
      var fileNumber = 0;
      for (final var bpmnProcessId : bpmnProcessIds) {
        final var filename = bpmnProcessId
            + "-"
            + ++fileNumber
            + ".bpmn";
        for (final var model : service
            .readBpmn(workflowModuleId, filename, bpmn(PROCESS.formatted(bpmnProcessId)), true)) {
          context = service.prepareBpmn(workflowModuleId, context, filename, model.getKey(), model.getValue());
        }
      }
      service.deployResources(workflowModuleId, context);

    }

    /**
     * Deploys one BPMN process id as two workflow modules, in the order given, and reads the
     * refusal of the second one.
     *
     * @param firstWorkflowModuleId The workflow module which deploys first
     * @param secondWorkflowModuleId The one which meets the taken id
     */
    private void refusesTheSecondOfTwoModules(
        final String firstWorkflowModuleId,
        final String secondWorkflowModuleId) {

      final var service = serviceScopedBy(
          scopingWith(NameClashAvoidance.NONE, firstWorkflowModuleId, secondWorkflowModuleId));

      deploy(service, firstWorkflowModuleId, "RiskAssessment");
      final var exception = Assertions.assertThrows(
          IllegalStateException.class,
          () -> deploy(service, secondWorkflowModuleId, "RiskAssessment"));

      // the wording is the core's, so only what a developer has to read is asserted here
      Assertions.assertTrue(exception.getMessage().contains("SAME identifier"), exception::getMessage);
      Assertions.assertTrue(exception.getMessage().contains("RiskAssessment"), exception::getMessage);
      Assertions.assertTrue(exception.getMessage().contains(firstWorkflowModuleId), exception::getMessage);
      Assertions.assertTrue(exception.getMessage().contains(secondWorkflowModuleId), exception::getMessage);
      // and the second module's model never reached the engine
      Assertions.assertEquals(1, engine.getDeployments().size());

    }

    @Test
    @DisplayName("Two workflow modules under one process id end the boot with the core's message")
    public void twoModulesUnderOneProcessIdEndTheBoot() {

      refusesTheSecondOfTwoModules("loan-approval", "loan-payout");

    }

    @Test
    @DisplayName("The same two workflow modules in the other order end the boot as well")
    public void theOtherDeploymentOrderEndsTheBootToo() {

      // which module deploys first is none of the application's doing, so a finding which
      // only shows up in one of the two orders is no finding
      refusesTheSecondOfTwoModules("loan-payout", "loan-approval");

    }

    @Test
    @DisplayName("This engine separates no pair of workflow modules")
    public void nothingIsSeparatedByThisEngine() {

      // what the core asks before it calls two plain ids a collision (see GAPS.md, entry 15)
      Assertions.assertFalse(service.ownIsolationSeparatesWorkflowModules("loan-approval", "loan-payout"));
      Assertions.assertFalse(service.ownIsolationSeparatesWorkflowModules("loan-payout", "loan-approval"));
      Assertions.assertFalse(service.ownIsolationSeparatesWorkflowModules("loan-approval", "loan-approval"));

    }

    @Test
    @DisplayName("Two workflow modules with process ids of their own deploy both")
    public void twoModulesWithDifferentProcessIdsDeployBoth() {

      final var service = serviceScopedBy(
          scopingWith(NameClashAvoidance.NONE, "loan-approval", "loan-payout"));

      deploy(service, "loan-approval", "RiskAssessment");
      Assertions.assertDoesNotThrow(() -> deploy(service, "loan-payout", "Payout"));

      Assertions.assertEquals(2, engine.getDeployments().size());

    }

    @Test
    @DisplayName("A prefix lets two workflow modules use the same process id")
    public void usePrefixKeepsTwoModulesApart() {

      final var service = serviceScopedBy(
          scopingWith(NameClashAvoidance.USE_PREFIX, "loan-approval", "loan-payout"));

      deploy(service, "loan-approval", "RiskAssessment");
      // what the engine sees are two ids, each carrying its module - the same pair of
      // modules which cannot both deploy 'RiskAssessment' under 'none'
      Assertions.assertDoesNotThrow(() -> deploy(service, "loan-payout", "RiskAssessment"));

      Assertions.assertEquals(2, engine.getDeployments().size());

    }

    @Test
    @DisplayName("A prefix collides where two modules compose one id")
    public void usePrefixCollidesWhereTwoModulesComposeOneId() {

      final var service = serviceScopedBy(
          scopingWith(NameClashAvoidance.USE_PREFIX, "loan", "loan__approval"));

      deploy(service, "loan", "approval__RiskAssessment");
      final var exception = Assertions.assertThrows(
          IllegalStateException.class,
          () -> deploy(service, "loan__approval", "RiskAssessment"));

      Assertions
          .assertTrue(
              exception.getMessage().contains("loan__approval__RiskAssessment"),
              exception::getMessage);

    }

    @Test
    @DisplayName("One workflow module declaring a process id twice deploys both files")
    public void theSameProcessIdTwiceInOneModuleIsNoCollision() {

      final var service = serviceScopedBy(scopingWith(NameClashAvoidance.NONE));

      // the check is about two modules meeting under one id, and here there is one module:
      // two files declaring one process id reach the engine and it keeps the last of them
      Assertions.assertDoesNotThrow(() -> deploy(service, "loan-approval", "RiskAssessment", "RiskAssessment"));

      Assertions.assertEquals(1, engine.getDeployments().size());
      Assertions.assertEquals(2, engine.getDeployments().get(0).resources().size());

    }

    @Test
    @DisplayName("One workflow module deployed twice collides with nothing")
    public void theSameModuleDeployedAgainIsNoCollision() {

      final var service = serviceScopedBy(scopingWith(NameClashAvoidance.NONE));

      deploy(service, "loan-approval", "RiskAssessment");
      Assertions.assertDoesNotThrow(() -> deploy(service, "loan-approval", "RiskAssessment"));

    }

    @Test
    @DisplayName("Without a core to ask nothing is compared")
    public void withoutScopingNothingIsChecked() {

      // a component built without a platform around it holds no support, and the plain
      // identifier is then the only answer there is
      deploy(service, "loan-approval", "RiskAssessment");
      Assertions.assertDoesNotThrow(() -> deploy(service, "loan-payout", "RiskAssessment"));

    }

  }

}
