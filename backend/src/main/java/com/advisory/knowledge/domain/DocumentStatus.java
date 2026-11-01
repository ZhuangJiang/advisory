package com.advisory.knowledge.domain;

/**
 * Document ingestion lifecycle. The database stores the name ({@code V1__init.sql} has a CHECK
 * constraint on those exact values); this enum is the single place that decides which names exist
 * and which of them are terminal.
 *
 * <p>Allowed moves are the linear pipeline {@code PENDING -> PARSING -> CHUNKING -> EMBEDDING ->
 * READY}, plus {@code FAILED} from any non-terminal state and {@code DELETED} from any state that is
 * not already deleted. Transitions are enforced with compare-and-set SQL, not by this enum.</p>
 */
public enum DocumentStatus {

    PENDING,
    PARSING,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED,
    DELETED;

    /** Terminal states are never picked up by the ingest worker again. */
    public boolean isTerminal() {
        return this == READY || this == FAILED || this == DELETED;
    }

    /** Parses a stored status name. Unknown values are a data-integrity problem, not a client error. */
    public static DocumentStatus from(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown document status in database: " + raw, e);
        }
    }
}
