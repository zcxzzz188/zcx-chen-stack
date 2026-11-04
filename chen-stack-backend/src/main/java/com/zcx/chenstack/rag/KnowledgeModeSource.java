package com.zcx.chenstack.rag;

/** 运行时知识模式来源；每次回答开始时读取一次，保证单轮使用同一模式。 */
public interface KnowledgeModeSource {
    String knowledgeMode();
}
