package io.vanillabp.pea.springboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * A {@link SpringDataUtil} stub, so the smoke tests need no database. It also says who
 * owns a workflow aggregate nobody else claims. Any use of either fails loudly, because
 * the smoke tests never store anything.
 * <p>
 * The platform asks for the persistence bean. An aggregate without a persistence ends the
 * startup. In that case the platform looks for a Spring Data repository and gets the
 * exception of the stub instead. A context of this module gets there easily. Scanning
 * picks up the {@code @WorkflowService} classes of the other tests, but not the persistence
 * beans they declare next to them. A double declared for a specific aggregate class still
 * wins over this one, and {@code TaskProcessingIntegrationTest} relies on that.
 */
@Configuration
public class TestPersistenceConfiguration {

  @Bean
  public AggregatePersistenceAware<Object> noPersistenceForUnclaimedAggregates() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {
        // Every aggregate is an Object, and Object is as far up the class tree as it
        // gets. So a double declared for a specific class always wins over this one.
        return Object.class;
      }

      @Override
      public Object save(
          final Object aggregate) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Object getAggregateId(
          final Object aggregate) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Object loadById(
          final Object aggregateId) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Class<?> getAggregateIdType() {
        // null means "cannot be told" in the contract. This double owns the stored form
        // of the id, as far as it owns anything at all.
        return null;
      }

    };

  }

  @Bean
  public SpringDataUtil testSpringDataUtil() {

    return new SpringDataUtil() {

      @Override
      public <O> CrudRepository<? super O, Object> getRepository(
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> CrudRepository<O, Object> getRepository(
          final Class<O> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <I> I getId(
          final Object entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public String getIdName(
          final Class<?> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Class<?> getIdType(
          final Class<?> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> O unproxy(
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> boolean isPersistedEntity(
          final Class<O> entityClass,
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

    };

  }

}
