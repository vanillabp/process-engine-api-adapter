package io.vanillabp.pea.quarkus.test;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The store of the end-to-end application's workflow aggregate - the platform reads
 * the persistence technology off it and attributes the JDBC phase-two outbox.
 */
@ApplicationScoped
public class PeaE2eAggregateRepository implements PanacheRepositoryBase<PeaE2eAggregate, String> {
}
