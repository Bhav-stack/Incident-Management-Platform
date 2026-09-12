package io.aegis.incident.repo;

import io.aegis.incident.domain.Evaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    List<Evaluation> findTop50ByOrderByCreatedAtDesc();

    /**
     * Ground-truth rows only ({@code correct} is null for shadow rows).
     * Returns, in order: true positives, false positives, false negatives,
     * true negatives, total.
     *
     * <p>True positives and true negatives are counted separately on purpose.
     * A single "correct" tally would fold correct {@code no_action} verdicts
     * into the positive class and inflate precision and recall.
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
    long[] verdictCounts();
}