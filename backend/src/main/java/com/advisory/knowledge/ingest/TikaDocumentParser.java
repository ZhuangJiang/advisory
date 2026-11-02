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
 * {@link DocumentParser} backed by Apache Tika.
 *
 * <p>Markdown and plain text are decoded as UTF-8 and kept verbatim, so their headings survive.
 * Everything else goes through Tika with a {@link ToXMLContentHandler}: Tika's XHTML output keeps
 * heading levels, lists, tables and paragraphs, which are then converted to Markdown-ish text (ATX
 * headings, {@code - } list items, pipe tables). That conversion is what makes structure-aware
 * chunking work for .docx/.pdf/.html files.</p>
 *
 * <p>Extraction is bounded: at most 20 MB of input bytes are read and the resulting text is capped
 * at 5 million characters. Parse failures and empty results surface as
 * {@link ErrorCode#VALIDATION_FAILED} with the user-facing message {@code "无法解析该文件"}. File
 * contents are never logged.</p>
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
            markdown = cap(markdown, MAX_TEXT_CHARS);
            if (markdown.isBlank()) {
                throw new AppException(ErrorCode.VALIDATION_FAILED, PARSE_ERROR_MESSAGE);
            }
            return new ParsedDocument(markdown, MarkdownStructure.sections(markdown));
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            // Deliberately no content in the message or the log: documents may be sensitive.
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
     * Rejects a file whose bytes do not match the format its extension promises. Without this a
     * garbage {@code .docx} is happily parsed by Tika's plain-text fallback and silently indexes
     * unrelated bytes.
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

    private static String cap(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, text.codePointCount(0, maxChars));
        return text.substring(0, end);
    }

    // ---------------------------------------------------------------- XHTML -> Markdown

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
                    // Metadata and scripts contribute no body text.
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
