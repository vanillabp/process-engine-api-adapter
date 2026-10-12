package io.vanillabp.pea.springboot.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import io.vanillabp.pea.springboot.TestPersistenceConfiguration;

/**
 * A test application which boots the full VanillaBP Spring Boot integration with the
 * Process-Engine-API adapter, so the deployment really runs. By default the adapter runs on
 * the mock. No {@code @WorkflowService} is present, so no {@code ProcessService} bean is
 * created and no real persistence is needed. The imported
 * {@link TestPersistenceConfiguration} provides a {@code SpringDataUtil} stub which is never
 * used, and the fallback persistence {@code noPersistenceForUnclaimedAggregates}.
 */
@SpringBootApplication
@Import(TestPersistenceConfiguration.class)
public class DeploymentIntegrationTestApplication {

}
