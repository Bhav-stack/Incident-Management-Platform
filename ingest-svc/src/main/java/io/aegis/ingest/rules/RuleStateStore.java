package io.aegis.ingest.rules;

/**
 * Cross-sample rule state (streak + fired episode flags), abstracted so rules
 * are unit-testable with an in-memory double and scale out with Redis.
 * Keys are "{service}:{metric}" — one logical rule evaluation per service.
 */
public interface RuleStateStore {

    /** Increment the breach streak, returning the new value. */
    long incrementStreak(String key);

    /** Clear streak and fired flag (a healthy sample resets the episode). */
    void reset(String key);

    /**
     * Atomically claim "fired" for the current episode.
     *
     * @return true only for the first claim; false while an episode is active
     */
    boolean markFired(String key);
}