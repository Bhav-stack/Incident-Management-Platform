package io.aegis.ingest.rules;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Cross-sample rule state (streak + fired episode flags), abstracted so rules
 * are unit-testable with an in-memory double and scale out with Redis.
 * Keys are "{service}:{metric}" — one logical rule evaluation per service.
 *
 * <p>The batch methods exist because the flush path evaluates a whole ordered
 * batch at once and the per-sample API cost one Redis round trip per sample,
 * which became the ingest ceiling once dedup was pipelined (see
 * docs/LOADTEST.md). {@link #snapshot} and {@link #applyStreaks} each take one
 * pipeline round trip for the entire batch, so state cost is per batch instead
 * of per sample.
 */
public interface RuleStateStore {

    /** Streak and current-episode-fired flag for one key. */
    record State(long streak, boolean fired) {

        public static final State EMPTY = new State(0, false);
    }

    /**
     * Reads streak and fired state for every key in <b>one</b> pipelined round
     * trip. Unknown keys map to {@link State#EMPTY}.
     */
    Map<String, State> snapshot(Collection<String> keys);

    /**
     * Pipelines the net effect of a whole ordered batch, in one round trip.
     *
     * @param finalStreaks key -> streak to persist; a value of 0 (or a missing
     *                     key) forgets the streak
     * @param resetKeys    keys that saw a healthy sample: delete both the streak
     *                     and the fired flag. Deletes are applied before the
     *                     streak writes, because a key that reset and then
     *                     breached again in the same batch appears in both
     *                     arguments and its fresh streak must survive.
     */
    void applyStreaks(Map<String, Long> finalStreaks, Set<String> resetKeys);

    /**
     * Atomically claim "fired" for the current episode. Kept per-sample on
     * purpose: it is the one cross-instance guard, and it runs only when a
     * candidate actually fires, i.e. at most once per key per batch.
     *
     * @return true only for the first claim; false while an episode is active
     */
    boolean markFired(String key);

    /**
     * Deletes only the fired flag. Needed when one batch contains a full
     * recover-then-breach cycle: the first episode claims the flag after the
     * state write, so the second episode must drop it before claiming again.
     * Called at most once per firing episode, never per healthy sample.
     */
    void clearFired(String key);
}
