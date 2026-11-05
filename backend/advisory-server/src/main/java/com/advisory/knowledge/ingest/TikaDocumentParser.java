package com.advisory.knowledge.ingest;

import com.advisory.common.error.AppException;
import com.advisory.common.error.ErrorCode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于 Apache Tika 的 {@link DocumentParser} 实现。
 *
 * <p>Markdown 与纯文本按 UTF-8 解码后原样保留，标题因此不会丢失。其他格式统一交给 Tika 并用
 * {@link ToXMLContentHandler} 取 XHTML：Tika 的 XHTML 会保留标题层级、列表、表格与段落，随后再转换成
 * 接近 Markdown 的文本（ATX 标题、{@code - } 列表项、管道表格）。正是这一步转换，让结构化切片对
 * .docx/.pdf/.html 也能生效。</p>
 *
 * <p>解析有硬边界：最多读取 20 MB 输入字节，产出文本上限 500 万字符。解析失败或结果为空时以
 * {@link ErrorCode#VALIDATION_FAILED} 抛出，对用户展示统一文案 {@code "无法解析该文件"}。
 * 任何情况下都不打印文件内容。</p>
 */
public class TikaDocumentParser implements DocumentParser {

    static final String PARSE_ERROR_MESSAGE = "无法解析该文件";

    private static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 5 * 1024 * 1024;

    private static final String OOXML_EXTENSIONS = "docx xlsx pptx";
    private static final String ODF_EXTENSIONS = "odt ods odp";
    private static final String OLE2_EXTENSIONS = "doc xls ppt";
    private static final byte[] OLE2_MAGIC = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    /** 解析上传文档；内容永不进日志。 */
    @Override
    public ParsedDocument parse(InputStream in, String filename) {
        if (in == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, PARSE_ERROR_MESSAGE);
        }
        String name = filename == null ? "" : filename;
        String extension = extension(name);
        try {
            byte[] bytes = readLimited(in);
            validateSignature(bytes, extension);
            String markdown = isPlainText(extension)
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : extractWithTika(bytes, name);
            markdown = normalize(markdown);
            markdown = stripLeadingBom(markdown);
            markdown = cap(markdown, MAX_TEXT_CHARS);
            if (markdown.isBlank()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, PARSE_ERROR_MESSAGE);
            }
            return new ParsedDocument(markdown, MarkdownStructure.sections(markdown));
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            // 消息与日志里都刻意不带内容：文档可能包含敏感信息。
            throw new AppException(ErrorCode.VALIDATION_FAILED, PARSE_ERROR_MESSAGE);
        }
    }

    private static String extractWithTika(byte[] bytes, String filename) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        ToXMLContentHandler handler = new ToXMLContentHandler();
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        ParseContext context = new ParseContext();
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            parser.parse(input, handler, metadata, context);
        }
        return XhtmlToMarkdown.convert(handler.toString());
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_INPUT_BYTES) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, PARSE_ERROR_MESSAGE);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * 拒绝字节内容与扩展名声明的格式不符的文件。
     *
     * <p>没有这道校验，一个内容乱码的 {@code .docx} 会被 Tika 的纯文本兜底逻辑「成功」解析，
     * 然后把一堆毫不相干的字节静默索引进知识库。</p>
     *
     * @param in       文件内容
     * @param filename 文件名，用于取扩展名
     */
    private static void validateSignature(byte[] bytes, String extension) {
        if (bytes.length == 0) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, PARSE_ERROR_MESSAGE);
        }
        if (containsToken(OOXML_EXTENSIONS, extension) || containsToken(ODF_EXTENSIONS, extension)) {
            requirePrefix(bytes, new byte[]{'P', 'K'});
        } else if (extension.equals("pdf")) {
            requirePrefix(bytes, "%PDF".getBytes(StandardCharsets.US_ASCII));
        } else if (containsToken(OLE2_EXTENSIONS, extension)) {
            requirePrefix(bytes, OLE2_MAGIC);
        }
    }

    private static boolean containsToken(String spaceSeparated, String token) {
        return (" " + spaceSeparated + " ").contains(" " + token + " ");
    }

    private static void requirePrefix(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, PARSE_ERROR_MESSAGE);
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, PARSE_ERROR_MESSAGE);
            }
        }
    }

    private static boolean isPlainText(String extension) {
        return extension.equals("md") || extension.equals("markdown") || extension.equals("txt");
    }

    private static String extension(String filename) {
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 去掉单个 UTF-8 BOM，避免行首的 {@code \uFEFF} 让 ATX 标题无法被识别。 */
    static String stripLeadingBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    static String cap(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxChars);
        if (end > 0 && end < text.length()
                && Character.isLowSurrogate(text.charAt(end))
                && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    // ---------------------------------------------------------------- XHTML 转 Markdown

    private static final class XhtmlToMarkdown {

        private XhtmlToMarkdown() {
        }

        static String convert(String xhtml) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setNamespaceAware(false);
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document document = builder.parse(new InputSource(new StringReader(xhtml)));
                StringBuilder out = new StringBuilder(xhtml.length());
                render(document.getDocumentElement(), out);
                return tidy(out.toString());
            } catch (Exception e) {
                return tidy(fallback(xhtml));
            }
        }

        private static void render(Node node, StringBuilder out) {
            if (node == null) {
                return;
            }
            short type = node.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                out.append(node.getNodeValue());
                return;
            }
            if (type != Node.ELEMENT_NODE) {
                renderChildren(node, out);
                return;
            }

            switch (localName(node)) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    String text = inline(node);
                    if (!text.isEmpty()) {
                        int level = localName(node).charAt(1) - '0';
                        out.append("\n\n").append("#".repeat(level)).append(' ').append(text).append("\n\n");
                    }
                }
                case "p" -> {
                    String text = inline(node);
                    if (!text.isEmpty()) {
                        out.append("\n\n").append(text).append("\n\n");
                    }
                }
                case "br" -> out.append('\n');
                case "li" -> {
                    String text = inline(node);
                    if (!text.isEmpty()) {
                        out.append("\n- ").append(text).append('\n');
                    }
                }
                case "pre" -> {
                    String text = rawText(node);
                    if (!text.isBlank()) {
                        out.append("\n\n```\n").append(text.stripTrailing()).append("\n```\n\n");
                    }
                }
                case "table" -> renderTable(node, out);
                case "head", "script", "style", "meta", "link" -> {
                    // 元数据与脚本不产生正文，直接跳过。
                }
                default -> renderChildren(node, out);
            }
        }

        private static void renderTable(Node table, StringBuilder out) {
            List<Node> rows = new ArrayList<>();
            collect(table, "tr", rows);
            if (rows.isEmpty()) {
                return;
            }
            out.append("\n\n");
            boolean headerSeparatorWritten = false;
            for (Node row : rows) {
                List<String> cells = new ArrayList<>();
                for (Node cell : childElements(row)) {
                    String cellName = localName(cell);
                    if (cellName.equals("td") || cellName.equals("th")) {
                        cells.add(inline(cell));
                    }
                }
                if (cells.isEmpty()) {
                    continue;
                }
                out.append("| ").append(String.join(" | ", cells)).append(" |\n");
                if (!headerSeparatorWritten) {
                    out.append('|');
                    for (int i = 0; i < cells.size(); i++) {
                        out.append(" --- |");
                    }
                    out.append('\n');
                    headerSeparatorWritten = true;
                }
            }
            out.append('\n');
        }

        private static void renderChildren(Node node, StringBuilder out) {
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                render(children.item(i), out);
            }
        }

        private static String inline(Node node) {
            StringBuilder out = new StringBuilder();
            appendInline(node, out);
            return out.toString().replaceAll("\\s+", " ").strip();
        }

        private static void appendInline(Node node, StringBuilder out) {
            short type = node.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                out.append(node.getNodeValue());
                return;
            }
            if (type != Node.ELEMENT_NODE) {
                NodeList children = node.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    appendInline(children.item(i), out);
                }
                return;
            }
            if (localName(node).equals("br")) {
                out.append(' ');
                return;
            }
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                appendInline(children.item(i), out);
            }
        }

        private static String rawText(Node node) {
            StringBuilder out = new StringBuilder();
            appendRaw(node, out);
            return out.toString();
        }

        private static void appendRaw(Node node, StringBuilder out) {
            short type = node.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                out.append(node.getNodeValue());
                return;
            }
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                appendRaw(children.item(i), out);
            }
        }

        private static List<Node> childElements(Node node) {
            List<Node> elements = new ArrayList<>();
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    elements.add(children.item(i));
                }
            }
            return elements;
        }

        private static void collect(Node node, String name, List<Node> matches) {
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                if (localName(child).equals(name)) {
                    matches.add(child);
                } else {
                    collect(child, name, matches);
                }
            }
        }

        private static String localName(Node node) {
            String name = node.getNodeName();
            int colon = name.indexOf(':');
            return (colon >= 0 ? name.substring(colon + 1) : name).toLowerCase(Locale.ROOT);
        }

        private static String tidy(String text) {
            String normalized = normalize(text);
            normalized = normalized.replaceAll("[ \\t]+\\n", "\n");
            normalized = normalized.replaceAll("\\n{3,}", "\n\n");
            return normalized.strip();
        }

        private static String fallback(String xhtml) {
            return xhtml.replaceAll("(?s)<[^>]+>", " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&amp;", "&");
        }
    }
}
