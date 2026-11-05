package com.advisory.knowledge.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析器与切片器共用的 Markdown 小工具：ATX 标题语法、围栏代码块识别，以及标题章节抽取。
 *
 * <p>抽成独立工具类是为了让「什么算标题」「什么算代码块」只有一处定义，否则解析与切片迟早会给出不同答案。</p>
 */
final class MarkdownStructure {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*)$");
    private static final Pattern TRAILING_HASHES = Pattern.compile("[ \\t]+#+$");

    private MarkdownStructure() {
    }

    static Matcher headingMatcher(String line) {
        return HEADING.matcher(line);
    }

    static String headingText(String captured) {
        return TRAILING_HASHES.matcher(captured.strip()).replaceFirst("").strip();
    }

    /**
     * 判断该行是否开启了一个围栏代码块。
     *
     * @param line 待判断的行
     * @return 开启时返回围栏标记（反引号或波浪号）的个数，否则返回 {@code 0}
     */
    static int fenceLength(String strippedLeading) {
        if (strippedLeading.isEmpty()) {
            return 0;
        }
        char marker = strippedLeading.charAt(0);
        if (marker != '`' && marker != '~') {
            return 0;
        }
        int length = 0;
        while (length < strippedLeading.length() && strippedLeading.charAt(length) == marker) {
            length++;
        }
        return length >= 3 ? length : 0;
    }

    static boolean isClosingFence(String strippedLeading, char marker, int openingLength) {
        int length = 0;
        while (length < strippedLeading.length() && strippedLeading.charAt(length) == marker) {
            length++;
        }
        return length >= openingLength && strippedLeading.substring(length).isBlank();
    }

    static List<ParsedSection> sections(String markdown) {
        List<ParsedSection> sections = new ArrayList<>();
        List<Heading> stack = new ArrayList<>();
        StringBuilder body = null;
        int level = 0;
        String heading = "";
        String path = "";

        boolean inFence = false;
        char fenceMarker = 0;
        int fenceLength = 0;

        for (String line : markdown.split("\n", -1)) {
            String leading = line.stripLeading();
            if (inFence) {
                if (body != null) {
                    body.append(line).append('\n');
                }
                if (isClosingFence(leading, fenceMarker, fenceLength)) {
                    inFence = false;
                }
                continue;
            }
            int fence = fenceLength(leading);
            if (fence > 0) {
                inFence = true;
                fenceMarker = leading.charAt(0);
                fenceLength = fence;
                if (body != null) {
                    body.append(line).append('\n');
                }
                continue;
            }

            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                if (body != null) {
                    sections.add(new ParsedSection(level, heading, path, trimBlankEdges(body)));
                }
                level = matcher.group(1).length();
                heading = headingText(matcher.group(2));
                while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= level) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(new Heading(level, heading));
                path = joinPath(stack);
                body = new StringBuilder();
            } else if (body != null) {
                body.append(line).append('\n');
            }
        }
        if (body != null) {
            sections.add(new ParsedSection(level, heading, path, trimBlankEdges(body)));
        }
        return sections;
    }

    static String joinPath(List<Heading> stack) {
        StringBuilder path = new StringBuilder();
        for (Heading heading : stack) {
            if (path.length() > 0) {
                path.append(" > ");
            }
            path.append(heading.text());
        }
        return path.toString();
    }

    private static String trimBlankEdges(CharSequence value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.subSequence(start, end).toString();
    }

    record Heading(int level, String text) {
    }
}
