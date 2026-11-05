package com.advisory.chat;

import com.advisory.support.AdvisoryFixture;

/**
 * Task 12 的夹具。做成一个有名字的子类，是为了让 chat 用例读起来就是「chat 夹具」，
 * 而真正的数据行仍住在 {@link AdvisoryFixture} 里（自 Task 13 起由 agent 与 audit 用例共用）。
 */
abstract class ChatFixture extends AdvisoryFixture {
}
