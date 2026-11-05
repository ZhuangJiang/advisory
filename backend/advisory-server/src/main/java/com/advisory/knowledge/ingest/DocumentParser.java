package com.advisory.knowledge.ingest;

import java.io.InputStream;

/**
 * 把上传的文档转换成接近 Markdown 的文本，以及按标题切分出的章节。
 *
 * <p>实现方绝不能打印文件内容：文档里可能包含敏感信息，日志一旦落盘就无法收回。</p>
 */
public interface DocumentParser {

    /**
     * 解析文档。
     *
     * @param in       文档字节流；生命周期由调用方负责，本方法不关闭它
     * @param filename 原始文件名，用于取扩展名并作为 Tika 的类型提示
     * @return 解析出的 Markdown 文本与标题章节
     * @throws com.advisory.common.error.AppException 文件无法解析或解析不出任何文本时，
     *         以 {@code VALIDATION_FAILED} 抛出
     */
    ParsedDocument parse(InputStream in, String filename);
}
