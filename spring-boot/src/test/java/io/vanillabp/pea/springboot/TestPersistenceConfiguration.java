package io.vanillabp.pea.springboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * Provides a {@link SpringDataUtil} stub so the smoke tests do not need a database, and
 * says who owns a workflow aggregate nobody else claims. Any usage of either fails loudly:
 * the skeleton tests never persist anything.
 * <p>
 * The second bean is what the platform asks for. An aggregate without a
 * persistence ends the startup, because the fallback looks for a Spring Data
 * repository and gets the stub's exception instead - and a context of this module reaches
 * that state easily, since scanning picks up the {@code @WorkflowService} classes of the
 * other tests without the persistence beans they declare next to themselves. A double
 * declared for a specific aggregate class still wins over this one, which is what
 * {@code TaskProcessingIntegrationTest} relies on.
 */
@Configuration
public class TestPersistenceConfiguration {

  @Bean
  public io.vanillabp.integration.spi.AggregatePersistenceAware<Object> noPersistenceForUnclaimedAggregates() {

    return new io.vanillabp.integration.spi.AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {
        // every aggregate is an Object, and at the greatest inheritance distance there is,
        // so a double declared for a specific class always wins over this one
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
        // the contract's "not determinable": this double owns the serialized form, as far
        // as it owns anything at all
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
