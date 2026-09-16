package io.vanillabp.pea.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.springboot.TestPersistenceConfiguration;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * What a <code>&#64;TaskParam</code> receives on this adapter. The aggregate shares a
 * decimal, a big integer, a float and a long, the start command carries them to the
 * engine, and the engine hands them back as the payload of a task. Nothing between the
 * aggregate and the handler serializes anything here, so what a handler reads is decided
 * by the platform's value conversion alone.
 * <p>
 * The conversion itself belongs to the platform and is tested there. What these tests add
 * is the round trip through THIS engine: a number which survives arrives, a number which
 * would be cut down fails the task instead, and the reason the engine is told is the
 * message the platform wrote.
 */
@SpringBootTest(
    classes = TaskParameterTypesIntegrationTest.TaskParameterTypesApplication.class,
    properties = {
        "vanillabp.adapters.pea.type=process-engine-api", "vanillabp.adapters.pea.name-clash-avoidance=none", "vanillabp.prioritized-adapters=pea", "vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/paramtypes"
    })
@ExtendWith(SuppressOutputExtension.class)
public class TaskParameterTypesIntegrationTest {

  private static final String PROCESS = "PeaParamProcess";

  private static final String AGGREGATE_ID = "9001";

  /**
   * The decimal the aggregate shares. Its scale is part of the value here: a
   * <code>BigDecimal</code> parameter reads it back unchanged, and a
   * <code>Double</code> parameter reads the same number without it.
   */
  private static final BigDecimal TOTAL = new BigDecimal("120.50");

  /**
   * One more than a <code>Double</code> can tell apart from its neighbour.
   */
  private static final BigInteger HUGE = new BigInteger("9007199254740993");

  // the test's nested classes are excluded from Spring's component scan
  // (TestTypeExcludeFilter) - configuration and workflow service are imported
  // explicitly
  @SpringBootApplication
  @Import({
      TestPersistenceConfiguration.class, TaskParameterTypesConfiguration.class, PeaParamWorkflowService.class
  })
  public static class TaskParameterTypesApplication {
  }

  public static class PeaParamAggregate {

    @Getter
    String id;

    @Getter
    BigDecimal total;

    @Getter
    BigInteger huge;

    @Getter
    Float rate;

    @Getter
    Long count;

    @Getter
    PeaParamOrder order;

  }

  /**
   * A value of the application's own, nested in the aggregate. The accessor is spelled
   * out because the sync model reads JavaBean properties and a record's component
   * accessor is not one.
   *
   * @param total The same decimal the aggregate shares at the top level
   */
  public record PeaParamOrder(BigDecimal total) {

    public BigDecimal getTotal() {

      return total;

    }

  }

  /**
   * What the handlers read, keyed by the task definition which read it. A name missing
   * here belongs to a handler which never ran.
   */
  static final Map<String, Object> RECEIVED = new ConcurrentHashMap<>();

  @Configuration
  public static class TaskParameterTypesConfiguration {

    static final Map<String, PeaParamAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    AggregatePersistenceAware<PeaParamAggregate> peaParamPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<PeaParamAggregate> getAggregateClass() {
          return PeaParamAggregate.class;
        }

        @Override
        public PeaParamAggregate save(
            final PeaParamAggregate aggregate) {
          AGGREGATES.put(aggregate.id, aggregate);
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final PeaParamAggregate aggregate) {
          return aggregate.id;
        }

        @Override
        public String getAggregateIdName() {
          return "id";
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public PeaParamAggregate loadById(
            final Object aggregateId) {
          return AGGREGATES.get(aggregateId);
        }

      };

    }

    @Bean
    DataSource peaParamDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource peaParamDataSource) {

      return new DataSourceTransactionManager(peaParamDataSource);

    }

  }

  @Service
  @WorkflowService(
      workflowAggregateClass = PeaParamAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class PeaParamWorkflowService {

    private final ProcessService<PeaParamAggregate> processService;

    public PeaParamWorkflowService(
        final ProcessService<PeaParamAggregate> processService) {

      this.processService = processService;

    }

    public PeaParamAggregate start(
        final PeaParamAggregate aggregate) {

      return processService.startWorkflow(aggregate);

    }

    @WorkflowTask(taskDefinition = "totalAsBigDecimal")
    public void totalAsBigDecimal(
        final PeaParamAggregate aggregate,
        @TaskParam("total") final BigDecimal total) {

      RECEIVED.put("totalAsBigDecimal", total);

    }

    @WorkflowTask(taskDefinition = "totalAsDouble")
    public void totalAsDouble(
        final PeaParamAggregate aggregate,
        @TaskParam("total") final Double total) {

      RECEIVED.put("totalAsDouble", total);

    }

    @WorkflowTask(taskDefinition = "totalAsInt")
    public void totalAsInt(
        final PeaParamAggregate aggregate,
        @TaskParam("total") final int total) {

      RECEIVED.put("totalAsInt", total);

    }

    @WorkflowTask(taskDefinition = "rateAsDouble")
    public void rateAsDouble(
        final PeaParamAggregate aggregate,
        @TaskParam("rate") final Double rate) {

      RECEIVED.put("rateAsDouble", rate);

    }

    @WorkflowTask(taskDefinition = "countAsLong")
    public void countAsLong(
        final PeaParamAggregate aggregate,
        @TaskParam("count") final long count) {

      RECEIVED.put("countAsLong", count);

    }

    @WorkflowTask(taskDefinition = "countAsInt")
    public void countAsInt(
        final PeaParamAggregate aggregate,
        @TaskParam("count") final int count) {

      RECEIVED.put("countAsInt", count);

    }

    @WorkflowTask(taskDefinition = "hugeAsLong")
    public void hugeAsLong(
        final PeaParamAggregate aggregate,
        @TaskParam("huge") final long huge) {

      RECEIVED.put("hugeAsLong", huge);

    }

    @WorkflowTask(taskDefinition = "hugeAsDouble")
    public void hugeAsDouble(
        final PeaParamAggregate aggregate,
        @TaskParam("huge") final Double huge) {

      RECEIVED.put("hugeAsDouble", huge);

    }

    @WorkflowTask(taskDefinition = "orderAsBigDecimal")
    public void orderAsBigDecimal(
        final PeaParamAggregate aggregate,
        @TaskParam("order") final BigDecimal order) {

      RECEIVED.put("orderAsBigDecimal", order);

    }

    @WorkflowTask(taskDefinition = "orderAsObject")
    public void orderAsObject(
        final PeaParamAggregate aggregate,
        @TaskParam("order") final Object order) {

      RECEIVED.put("orderAsObject", order);

    }

  }

  @Autowired
  private PeaParamWorkflowService paramWorkflow;

  @Autowired
  private InMemoryProcessEngine engine;

  @Autowired
  private TransactionTemplate transactionTemplate;

  /**
   * What the start command carried to the engine, read once and reused by every test:
   * the values are constant and starting the workflow again would only repeat the wait
   * for phase two.
   */
  private static Map<String, Object> pushedVariables;

  @BeforeEach
  public void shareTheAggregateOnce() throws Exception {

    RECEIVED.clear();
    // keep the startup subscriptions and the started instance - only the per-test
    // recordings are cleared
    engine.clearTaskRecordings();
    if (pushedVariables != null) {
      return;
    }

    final var aggregate = new PeaParamAggregate();
    aggregate.id = AGGREGATE_ID;
    aggregate.total = TOTAL;
    aggregate.huge = HUGE;
    aggregate.rate = Float.valueOf(0.1f);
    aggregate.count = Long.valueOf(3000000000L);
    aggregate.order = new PeaParamOrder(TOTAL);
    TaskParameterTypesConfiguration.AGGREGATES.put(AGGREGATE_ID, aggregate);

    transactionTemplate.execute(status -> paramWorkflow.start(aggregate));
    // phase two dispatches the start after the caller's transaction committed
    awaitUntil(
        () -> !engine.getStartedInstances().isEmpty(),
        "the start to be dispatched after the commit");
    pushedVariables = engine.getStartedInstances().getLast().variables();

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 15000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(50);
    }

  }

  /**
   * Delivers the task of one test, carrying what the start command carried. The engine
   * narrows that to what the subscription asked for, which is how the handler gets its
   * own variable and the aggregate-id variable and nothing else.
   *
   * @param taskDefinition The task definition the test delivers
   * @return What the handler read, or <code>null</code> where it never ran
   */
  private Object deliver(
      final String taskDefinition) {

    engine.deliverTask("task-"
        + taskDefinition, taskDefinition, PROCESS, pushedVariables);
    return RECEIVED.get(taskDefinition);

  }

  /**
   * @param taskDefinition The task definition the test delivered
   * @return Why the engine was told the task failed
   */
  private String reasonTheTaskFailed(
      final String taskDefinition) {

    assertFalse(RECEIVED.containsKey(taskDefinition), "the handler must not have run");
    assertTrue(engine.getCompletedTasks().isEmpty(), "a failed task must not be completed");
    assertEquals(1, engine.getFailedTasks().size(), "exactly one task was delivered");
    return engine.getFailedTasks().getFirst().reason();

  }

  @Test
  @DisplayName("A BigDecimal parameter keeps the scale the aggregate shared, because nothing here serializes it")
  public void aDecimalKeepsItsScale() {

    // the Process-Engine-API type is Map<String, Object> from end to end, so the
    // BigDecimal the aggregate shared is the very object the handler reads
    assertEquals(new BigDecimal("120.50"), deliver("totalAsBigDecimal"));

  }

  @Test
  @DisplayName("The same decimal bound to a Double arrives without its scale, which is the same number")
  public void aDecimalBoundToADoubleDropsOnlyTheScale() {

    assertEquals(Double.valueOf(120.5d), deliver("totalAsDouble"));

  }

  @Test
  @DisplayName("The same decimal bound to an int fails the task rather than arriving as 120")
  public void aDecimalBoundToAnIntFailsTheTask() {

    deliver("totalAsInt");

    final var reason = reasonTheTaskFailed("totalAsInt");
    assertTrue(reason.contains("does not fit the parameter's type 'int'"), reason);
    assertTrue(reason.contains("which would hold '120'"), reason);

  }

  @Test
  @DisplayName("A Float of 0.1f bound to a Double arrives as 0.1, not as the double it widens to")
  public void aFloatBoundToADoubleKeepsItsNumber() {

    // a Float widened by doubleValue() used to arrive as 0.10000000149011612 here
    assertEquals(Double.valueOf(0.1d), deliver("rateAsDouble"));

  }

  @Test
  @DisplayName("A Long above the int range bound to a long still arrives, which version 1 accepted too")
  public void aLongBoundToALongStillArrives() {

    assertEquals(3000000000L, deliver("countAsLong"));

  }

  @Test
  @DisplayName("The same Long bound to an int fails the task rather than wrapping around")
  public void aLongBoundToAnIntFailsTheTask() {

    deliver("countAsInt");

    final var reason = reasonTheTaskFailed("countAsInt");
    assertTrue(reason.contains("The value '3000000000'"), reason);
    assertTrue(reason.contains("which would hold '-1294967296'"), reason);

  }

  @Test
  @DisplayName("A BigInteger bound to a long arrives exactly")
  public void aBigIntegerBoundToALongArrivesExactly() {

    assertEquals(9007199254740993L, deliver("hugeAsLong"));

  }

  @Test
  @DisplayName("The same BigInteger bound to a Double fails the task, because a Double cannot tell it from its neighbour")
  public void aBigIntegerBoundToADoubleFailsTheTask() {

    deliver("hugeAsDouble");

    final var reason = reasonTheTaskFailed("hugeAsDouble");
    assertTrue(reason.contains("which would hold '9.007199254740992E15'"), reason);

  }

  @Test
  @DisplayName("A nested value is the whole map here, and everything but Object refuses it")
  public void aNestedValueBoundToANumberFailsTheTask() {

    deliver("orderAsBigDecimal");

    // the message of a type which cannot convert at all, unchanged by the number rule
    final var reason = reasonTheTaskFailed("orderAsBigDecimal");
    assertTrue(reason.contains("cannot be converted to the parameter's type"), reason);

  }

  @Test
  @DisplayName("The same nested value bound to Object arrives as the map the engine holds")
  public void aNestedValueBoundToObjectArrivesAsAMap() {

    final var order = assertInstanceOf(LinkedHashMap.class, deliver("orderAsObject"));

    assertEquals(TOTAL, order.get("total"), "the nested decimal keeps its scale as well");

  }

}
