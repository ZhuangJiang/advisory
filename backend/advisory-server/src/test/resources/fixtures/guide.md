# 咨询平台指南

## 第一章 概述

咨询平台用于管理知识库、文档与智能问答会话。本指南约定文档解析与切分的规则。

### 1.1 背景

平台需要把上传的文档整理成可检索的知识片段，因此先解析出结构化文本，再按标题层级和篇幅切分。

| 模块 | 职责 | 负责人 |
| --- | --- | --- |
| 解析 | 文档转 Markdown | 张工 |
| 切分 | 结构感知分块 | 李工 |
| 检索 | 向量召回 | 王工 |

### 1.2 示例

下面是一段示例代码，切分时应当作为整体保留：

```java
public class Example {
    public static void main(String[] args) {
        System.out.println("hello advisory");
    }
}
```

## 第二章 流程

文档上传后依次经过解析、切分、向量化三个阶段，最终进入就绪状态。

### 2.1 状态机

状态依次为 PENDING、PARSING、CHUNKING、EMBEDDING、READY。
