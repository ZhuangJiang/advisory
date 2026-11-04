package com.advisory.chat.orchestration;

/** The fixed four phases of one question-answering run (spec §11); the order is not configurable. */
public enum Phase {
    PLAN,
    RETRIEVE,
    REFLECT,
    ANSWER
}
