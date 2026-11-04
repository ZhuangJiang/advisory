package com.advisory.chat.orchestration;

/** 一次问答运行中固定的四个阶段（spec §11）；顺序不可配置。 */
public enum Phase {
    PLAN,
    RETRIEVE,
    REFLECT,
    ANSWER
}
