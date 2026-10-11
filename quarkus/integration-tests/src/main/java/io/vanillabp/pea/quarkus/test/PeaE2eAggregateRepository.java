package io.vanillabp.pea.quarkus.test;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The store of the workflow aggregate of the end-to-end application. The platform reads the
 * persistence technology from it, and so picks the JDBC outbox for phase two.
 */
@ApplicationScoped
public class PeaE2eAggregateRepository implements PanacheRepositoryBase<PeaE2eAggregate, String> {
}
