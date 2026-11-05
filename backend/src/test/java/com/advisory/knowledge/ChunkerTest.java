package com.advisory.knowledge;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import com.advisory.knowledge.ingest.ChunkConfig;
import com.advisory.knowledge.ingest.DocumentParser;
import com.advisory.knowledge.ingest.ParsedDocument;
import com.advisory.knowledge.ingest.ParsedSection;
import com.advisory.knowledge.ingest.StructureAwareChunker;
import com.advisory.knowledge.ingest.TextChunk;
import com.advisory.knowledge.ingest.TikaDocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkerTest {

    private static final ChunkConfig CONFIG = ChunkConfig.DEFAULT;

    private final DocumentParser parser = new TikaDocumentParser();
    private final StructureAwareChunker chunker = new StructureAwareChunker();

    @Test
    void splitsByMarkdownHeadings() throws IOException {
        ParsedDocument doc = parseFixture("guide.md");

        List<TextChunk> chunks = chunker.split(doc, CONFIG);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks).extracting(TextChunk::headingPath)
                .contains("咨询平台指南 > 第一章 概述")
                .contains("咨询平台指南 > 第一章 概述 > 1.1 背景")
                .contains("咨询平台指南 > 第二章 流程")
                .contains("咨询平台指南 > 第二章 流程 > 2.1 状态机");
        // 嵌套标题以完整的「A > B > C」路径表示。
        assertThat(chunks)
                .filteredOn(c -> c.headingPath().contains("1.1 背景"))
                .allSatisfy(c -> assertThat(c.headingPath().split(" > ")).hasSize(3));
    }

    @Test
    void neverTruncatesTable() {
        StringBuilder body = new StringBuilder("""
                # 表格测试

                ## 数据表

                下面是一张很长的表格，切分时必须保持完整。
                """);
        body.append("\n| 编号 | 名称 | 说明 |\n| --- | --- | --- |\n");
        for (int i = 1; i <= 80; i++) {
            body.append("| ROW-%04d | 名称%d | 这是第%d行的说明文字 |\n".formatted(i, i, i));
        }
        String table = body.substring(body.indexOf("| 编号"));

        List<TextChunk> chunks = chunker.split(new ParsedDocument(body.toString(), List.of()), CONFIG);

        List<TextChunk> tableChunks = chunks.stream()
                .filter(c -> c.content().contains("ROW-"))
                .toList();
        assertThat(tableChunks).hasSize(1);
        TextChunk tableChunk = tableChunks.get(0);
        assertThat(tableChunk.content()).contains(table.strip());
        assertThat(codePointLength(tableChunk.content())).isGreaterThan(CONFIG.maxChars());
        assertThat(chunks)
                .filteredOn(c -> !c.content().contains("ROW-"))
                .allSatisfy(c -> assertThat(c.content()).doesNotContain("|"));
    }

    @Test
    void neverTruncatesCodeBlock() {
        StringBuilder body = new StringBuilder("""
                # 代码测试

                ## 示例

                说明文字。
                """);
        body.append("\n```text\n");
        for (int i = 1; i <= 200; i++) {
            body.append("LINE-%04d 这是一行示例代码内容\n".formatted(i));
        }
        body.append("```\n");

        List<TextChunk> chunks = chunker.split(new ParsedDocument(body.toString(), List.of()), CONFIG);

        List<TextChunk> codeChunks = chunks.stream()
                .filter(c -> c.content().contains("LINE-"))
                .toList();
        assertThat(codeChunks).hasSize(1);
        TextChunk codeChunk = codeChunks.get(0);
        assertThat(codeChunk.content()).contains("LINE-0001").contains("LINE-0200");
        assertThat(codePointLength(codeChunk.content())).isGreaterThan(CONFIG.maxChars());
        assertThat(chunks)
                .filteredOn(c -> !c.content().contains("LINE-"))
                .allSatisfy(c -> assertThat(c.content()).doesNotContain("```"));
    }

    @Test
    void longSectionFallsBackToOverlap() {
        String prose = chineseProse(5_000);

        List<TextChunk> chunks = chunker.split(new ParsedDocument(prose, List.of()), CONFIG);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(4);
        assertThat(chunks).allSatisfy(c ->
                assertThat(codePointLength(c.content()))
                        .as("chunk must fit maxChars")
                        .isLessThanOrEqualTo(CONFIG.maxChars()));
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).content();
            String current = chunks.get(i).content();
            int overlap = sharedSuffixPrefix(previous, current);
            int previousLength = codePointLength(previous);
            assertThat(overlap)
                    .as("adjacent chunks %d/%d must overlap (prev=%d code points)", i - 1, i, previousLength)
                    .isPositive()
                    .isGreaterThanOrEqualTo((int) Math.floor(previousLength * 0.08))
                    .isLessThanOrEqualTo((int) Math.ceil(previousLength * 0.16));
        }
    }

    @Test
    void shortRemainderMerges() {
        String prose = chineseProse(3_000);

        List<TextChunk> chunks = chunker.split(new ParsedDocument(prose, List.of()), CONFIG);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks).allSatisfy(c ->
                assertThat(codePointLength(c.content())).isLessThanOrEqualTo(CONFIG.maxChars()));
        TextChunk last = chunks.get(chunks.size() - 1);
        assertThat(codePointLength(last.content()))
                .as("last chunk must not be an orphan tail")
                .isGreaterThanOrEqualTo(CONFIG.minChars());
    }

    @Test
    void seqIsContiguous() throws IOException {
        List<TextChunk> chunks = chunker.split(parseFixture("guide.md"), CONFIG);

        assertThat(chunks).isNotEmpty();
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).seq()).isEqualTo(i);
        }
    }

    @Test
    void noSurrogateSplit() {
        StringBuilder body = new StringBuilder();
        while (codePointLength(body.toString()) < 3_000) {
            body.append("咨询😀内容。");
        }

        List<TextChunk> chunks = chunker.split(new ParsedDocument(body.toString(), List.of()), CONFIG);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> {
            byte[] utf8 = c.content().getBytes(StandardCharsets.UTF_8);
            assertThat(new String(utf8, StandardCharsets.UTF_8))
                    .as("chunk must not contain an unpaired surrogate")
                    .isEqualTo(c.content());
            c.content().codePoints().forEach(cp -> {
                if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                    throw new AssertionError("chunk contains a lone surrogate: U+" + Integer.toHexString(cp));
                }
            });
        });
    }

    @Test
    void parsesMarkdownVerbatim() throws IOException {
        String raw = readFixture("guide.md");

        ParsedDocument doc = parser.parse(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), "guide.md");

        assertThat(doc.markdown()).isEqualTo(raw.replace("\r\n", "\n"));
        assertThat(doc.sections()).isNotEmpty();
        assertThat(doc.sections())
                .filteredOn(s -> s.heading().equals("1.1 背景"))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.level()).isEqualTo(3);
                    assertThat(s.headingPath()).isEqualTo("咨询平台指南 > 第一章 概述 > 1.1 背景");
                    assertThat(s.content()).contains("平台需要把上传的文档整理成可检索的知识片段");
                });
        assertThat(doc.sections())
                .filteredOn(s -> s.heading().equals("2.1 状态机"))
                .singleElement()
                .satisfies(s -> assertThat(s.content()).contains("PENDING、PARSING"));
    }

    @Test
    void parsesDocxWithHeadingStructure() throws IOException {
        byte[] docx = docxFixture();

        ParsedDocument doc = parser.parse(new ByteArrayInputStream(docx), "guide.docx");

        assertThat(doc.markdown()).contains("## 核心功能").contains("### 安装步骤");
        assertThat(doc.sections()).isNotEmpty();
        List<TextChunk> chunks = chunker.split(doc, CONFIG);
        assertThat(chunks).extracting(TextChunk::headingPath)
                .contains("产品手册 > 核心功能")
                .contains("产品手册 > 核心功能 > 安装步骤");
        assertThat(chunks).anySatisfy(c -> assertThat(c.content()).contains("第一步安装依赖"));
    }

    @Test
    void parsesPdfTextWithoutContainerDetectionCrash() throws IOException {
        // 回归用例：PDF 不是 ZIP，Tika 的容器探测会走到 commons-compress 的 tar/checksum 分支。
        // 一旦 commons-io 被依赖冲突压回 2.15.x（缺 ChecksumInputStream），这里就会抛
        // NoClassDefFoundError，而所有 docx（ZIP 魔数直接命中）照旧能解析——线上表现为
        // 「docx 全绿、PDF 全部卡在 PARSING」。见 pom.xml 里 commons-io 版本锁定的注释。
        byte[] pdf = pdfFixture();

        ParsedDocument doc = parser.parse(new ByteArrayInputStream(pdf), "report.pdf");

        // 这份 PDF 没有标题，sections 会是空的（正文归入默认节），所以只校验正文与切片都拿到内容。
        assertThat(doc.markdown())
                .contains("Carbon quota report")
                .contains("verify its emission boundary");
        assertThat(chunker.split(doc, CONFIG)).isNotEmpty();
    }

    @Test
    void rejectsUnparseableFile() {
        byte[] garbage = "this is definitely not a docx archive".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(garbage), "broken.docx"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsCorruptDocxContainer() {
        // 能通过 PK 魔数嗅探，但内部并非 OOXML 内容：Tika（POI）必然解析失败。
        byte[] bytes = new byte[36];
        bytes[0] = 'P';
        bytes[1] = 'K';
        bytes[2] = 3;
        bytes[3] = 4;
        byte[] junk = "broken central directory, not a real office package".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(junk, 0, bytes, 4, Math.min(junk.length, bytes.length - 4));

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(bytes), "corrupt.docx"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void stripsUtf8BomBeforeFirstHeading() {
        byte[] payload = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                """
                # 顶层标题

                正文从这里开始，用来产生第一个 chunk。

                ## 子标题

                子标题下面还有一段内容。
                """.getBytes(StandardCharsets.UTF_8));

        ParsedDocument doc = parser.parse(new ByteArrayInputStream(payload), "bom.md");

        assertThat(doc.sections()).isNotEmpty();
        assertThat(doc.sections().get(0).heading()).isEqualTo("顶层标题");

        List<TextChunk> chunks = chunker.split(doc, CONFIG);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).headingPath()).startsWith("顶层标题");
        assertThat(chunks).allSatisfy(c -> assertThat(c.content().startsWith("\uFEFF")).isFalse());
    }

    // ------------------------------------------------------------------ 辅助方法

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private ParsedDocument parseFixture(String name) throws IOException {
        return parser.parse(new ByteArrayInputStream(readFixture(name).getBytes(StandardCharsets.UTF_8)), name);
    }

    private static String readFixture(String name) throws IOException {
        try (InputStream in = ChunkerTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 生成带编号句子的确定性中文文本，并裁剪到 {@code target} 个码点。 */
    private static String chineseProse(int target) {
        StringBuilder sb = new StringBuilder(target + 64);
        int count = 0;
        for (int i = 1; count < target; i++) {
            String sentence = "这是第%04d句用于验证切分行为的中文测试内容。".formatted(i);
            sb.append(sentence);
            count += codePointLength(sentence);
        }
        String text = sb.toString();
        if (count > target) {
            text = text.substring(0, text.offsetByCodePoints(0, target));
        }
        return text;
    }

    /**
     * 在测试时用 Apache POI 现场生成 .docx 夹具，而不是往仓库里提交一个二进制文件。
     * 关键在于标题样式——Tika 正是靠它映射出 {@code <h1>}/{@code <h2>}/{@code <h3>}：
     * 只有段落的样式存在于 styles.xml 且名称以 "heading"/"Heading" 开头时，Tika 才会输出标题标签，
     * 所以这些样式必须显式注册。
     */
    private static byte[] docxFixture() throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addHeadingStyles(document);
            heading(document, "产品手册", 1);
            paragraph(document, "这是一段产品介绍文字。");
            heading(document, "核心功能", 2);
            paragraph(document, "核心功能包括知识库管理与智能问答。");
            heading(document, "安装步骤", 3);
            paragraph(document, "第一步安装依赖。");
            document.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 用 PDFBox 现场生成 .pdf 夹具。只写 ASCII 文本，避免测试依赖系统里是否装了中文字体。
     */
    private static byte[] pdfFixture() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA_BOLD, 16);
                content.newLineAtOffset(60, 740);
                content.showText("Carbon quota report");
                content.endText();
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 11);
                content.newLineAtOffset(60, 700);
                content.showText("The steel plant must verify its emission boundary every year.");
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** 注册 Heading1..Heading6 样式；Tika 是按样式名匹配的，光有样式 id 不够。 */    private static void addHeadingStyles(XWPFDocument document) {
        XWPFStyles styles = document.createStyles();
        for (int level = 1; level <= 6; level++) {
            CTStyle ctStyle = CTStyle.Factory.newInstance();
            ctStyle.setStyleId("Heading" + level);
            ctStyle.setType(STStyleType.PARAGRAPH);
            ctStyle.addNewName().setVal("Heading" + level);
            styles.addStyle(new XWPFStyle(ctStyle));
        }
    }

    private static void heading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + level);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
    }

    private static void paragraph(XWPFDocument document, String text) {
        XWPFRun run = document.createParagraph().createRun();
        run.setText(text);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String codePointPrefix(String value, int length) {
        return value.substring(0, value.offsetByCodePoints(0, length));
    }

    private static String codePointSuffix(String value, int length) {
        int total = codePointLength(value);
        return value.substring(value.offsetByCodePoints(value.length(), -length), value.length());
    }

    private static int sharedSuffixPrefix(String previous, String current) {
        int max = Math.min(codePointLength(previous), codePointLength(current));
        for (int length = max; length > 0; length--) {
            if (codePointSuffix(previous, length).equals(codePointPrefix(current, length))) {
                return length;
            }
        }
        return 0;
    }
}
