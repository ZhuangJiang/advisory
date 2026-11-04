package com.advisory.chat;

import com.advisory.support.AdvisoryFixture;

/**
 * Task 12's fixture, kept as a named subclass so the chat tests read as "chat fixture" while the
 * rows themselves live in {@link AdvisoryFixture} (shared with the agent and audit tests since
 * Task 13).
 */
abstract class ChatFixture extends AdvisoryFixture {
}
