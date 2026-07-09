package io.vanillabp.pea.springboot.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import io.vanillabp.pea.springboot.TestPersistenceConfiguration;

/**
 * Test application booting the full VanillaBP Spring Boot integration together with the
 * Process-Engine-API adapter (mock-backed by default) so the deployment lifecycle actually
 * runs. No {@code @WorkflowService} is present, so no {@code ProcessService} bean is created
 * and no real persistence is required - the imported {@link TestPersistenceConfiguration}
 * only provides a never-used {@code SpringDataUtil} stub.
 */
@SpringBootApplication
@Import(TestPersistenceConfiguration.class)
public class DeploymentIntegrationTestApplication {

}
