package com.advisory.chat.orchestration;

import java.util.List;

/**
 * REFLECT's structured output.
 *
 * @param sufficient   whether the accumulated snippets already support an answer
 * @param gaps         what is still missing (fed back to the next round's prompt)
 * @param nextQueries  queries for the next retrieval round, used in preference to {@code gaps}
 * @param draftAnswer  a draft built only from the snippets; the ANSWER fallback when the final call
 *                     times out
 */
public record ReflectResult(boolean sufficient, List<String> gaps, List<String> nextQueries,
                            String draftAnswer) {
}
