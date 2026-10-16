package io.aegis.incident.repo;

import io.aegis.incident.domain.Evaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    List<Evaluation> findTop50ByOrderByCreatedAtDesc();

    /**
     * Ground-truth rows only (correct is null for shadow rows).
     * Returns: true positives, false positives, false negatives,
     * true negatives, total.
     */
    @Query("""
            select
              sum(case when e.correct = true and e.expectedAction <> 'no_action' then 1 else 0 end),
              sum(case when e.correct = false and e.expectedAction = 'no_action' then 1 else 0 end),
sum(case when e.correct = false and e.expectedAction <> 'no_action' then 1 else 0 end),
              sum(case when e.correct = true and e.expectedAction = 'no_action' then 1 else 0 end),
              count(e)
            from Evaluation e
            where e.correct is not null
            """)
    List<Object[]> verdictCounts();
}