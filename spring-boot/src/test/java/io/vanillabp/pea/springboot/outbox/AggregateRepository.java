package io.vanillabp.pea.springboot.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AggregateRepository extends JpaRepository<Aggregate, Long> {

}
