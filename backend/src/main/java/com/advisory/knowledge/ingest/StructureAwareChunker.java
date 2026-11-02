package com.advisory.knowledge.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;

/**
 * Structure-aware chunker.
 *
 * <p>The Markdown is walked in document order while a heading stack produces
 * {@code "A > B > C"} paths. Fenced code blocks and Markdown tables are atomic: they become one
 * chunk even when longer than {@code maxChars}. Prose is split on sentence boundaries with a
 * {@code overlapRatio} suffix of the previous chunk repeated at the start of the next one, and every
 * prose chunk stays at or below {@code maxChars}. A trailing chunk below {@code minChars} is merged
 * or rebalanced so a section does not end with an orphan.</p>
 *
 * <p>All lengths are measured in Unicode code points and split points are always chosen on code
 * point boundaries, so surrogate pairs (emoji, rare CJK characters) are never cut in half.</p>
 */
public class StructureAwareChunker {

    private static final String SENTENCE_TERMINATORS = "。！？；.!?;";

    public List<TextChunk> split(ParsedDocument doc, ChunkConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        String markdown = doc == null ? "" : doc.markdown();
        markdown = markdown.replace("\r\n", "\n").replace('\r', '\n');

        List<Block> blocks = toBlocks(markdown);
        List<Piece> pieces = new ArrayList<>();
        int index = 0;
        while (index < blocks.size()) {
            String path = blocks.get(index).headingPath();
            int end = index + 1;
            while (end < blocks.size() && blocks.get(end).headingPath().equals(path)) {
                end++;
            }
            for (int i = index; i < end; i++) {
                Block block = blocks.get(i);
                if (block.atomic()) {
                    pieces.add(new Piece(path, block.text()));
                } else {
                    pieces.addAll(splitProse(path, block.text(), cfg));
                }
            }
            index = end;
        }

        List<TextChunk> chunks = new ArrayList<>(pieces.size());
        for (int seq = 0; seq < pieces.size(); seq++) {
            Piece piece = pieces.get(seq);
            chunks.add(new TextChunk(seq, piece.headingPath(), piece.text()));
        }
        return chunks;
    }

    // ---------------------------------------------------------------- structure walk

    private static List<Block> toBlocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        List<MarkdownStructure.Heading> stack = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        String prosePath = "";

        String[] lines = markdown.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String leading = line.stripLeading();

            int fence = MarkdownStructure.fenceLength(leading);
            if (fence > 0) {
                flushProse(blocks, prose, prosePath);
                char marker = leading.charAt(0);
                StringBuilder code = new StringBuilder(line);
                i++;
                while (i < lines.length) {
                    code.append('\n').append(lines[i]);
                    boolean closing = MarkdownStructure.isClosingFence(
                            lines[i].stripLeading(), marker, fence);
                    i++;
                    if (closing) {
                        break;
                    }
                }
                blocks.add(new Block(currentPath(stack), true, code.toString()));
                continue;
            }

            if (line.contains("|") && i + 1 < lines.length && lines[i + 1].contains("|")) {
                flushProse(blocks, prose, prosePath);
                StringBuilder table = new StringBuilder();
                while (i < lines.length && lines[i].contains("|")) {
                    if (table.length() > 0) {
                        table.append('\n');
                    }
                    table.append(lines[i]);
                    i++;
                }
                blocks.add(new Block(currentPath(stack), true, table.toString()));
                continue;
            }

            Matcher heading = MarkdownStructure.headingMatcher(line);
            if (heading.matches()) {
                flushProse(blocks, prose, prosePath);
                int level = heading.group(1).length();
                String text = MarkdownStructure.headingText(heading.group(2));
                while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= level) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(new MarkdownStructure.Heading(level, text));
                i++;
                continue;
            }

            String path = currentPath(stack);
            if (!path.equals(prosePath)) {
                flushProse(blocks, prose, prosePath);
                prosePath = path;
            }
            prose.append(line).append('\n');
            i++;
        }
        flushProse(blocks, prose, prosePath);
        return blocks;
    }

    private static void flushProse(List<Block> blocks, StringBuilder prose, String path) {
        if (prose.length() == 0) {
            return;
        }
        String text = prose.toString().strip();
        prose.setLength(0);
        if (!text.isEmpty()) {
            blocks.add(new Block(path, false, text));
        }
    }

    private static String currentPath(List<MarkdownStructure.Heading> stack) {
        return MarkdownStructure.joinPath(stack);
    }

    // ---------------------------------------------------------------- prose splitting

    private static List<Piece> splitProse(String path, String text, ChunkConfig cfg) {
        if (text.isEmpty()) {
            return List.of();
        }
        if (codePointLength(text) <= cfg.maxChars()) {
            return List.of(new Piece(path, text));
        }

        List<String> units = sentenceUnits(text, cfg.maxChars());
        List<List<String>> groups = pack(units, cfg);
        rebalanceTail(groups, cfg);

        List<String> contents = new ArrayList<>(groups.size());
        for (List<String> group : groups) {
            contents.add(String.join("", group));
        }
        applyOverlap(contents, cfg);

        List<Piece> pieces = new ArrayList<>(contents.size());
        for (String content : contents) {
            pieces.add(new Piece(path, content));
        }
        return pieces;
    }

    /** Splits into sentences that keep their terminator; oversized sentences are hard-split. */
    private static List<String> sentenceUnits(String text, int maxChars) {
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            current.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
            if (codePoint == '\n' || SENTENCE_TERMINATORS.indexOf(codePoint) >= 0) {
                while (i < text.length()) {
                    int next = text.codePointAt(i);
                    if (next == ' ' || next == '\t') {
                        current.appendCodePoint(next);
                        i += Character.charCount(next);
                    } else {
                        break;
                    }
                }
                addUnit(current, maxChars, units);
                current.setLength(0);
            }
        }
        addUnit(current, maxChars, units);
        return units;
    }

    private static void addUnit(StringBuilder current, int maxChars, List<String> units) {
        String unit = current.toString();
        if (unit.isEmpty()) {
            return;
        }
        int length = codePointLength(unit);
        if (length <= maxChars) {
            units.add(unit);
            return;
        }
        int start = 0;
        while (start < unit.length()) {
            int available = unit.codePointCount(start, unit.length());
            int take = Math.min(maxChars, available);
            int end = unit.offsetByCodePoints(start, take);
            units.add(unit.substring(start, end));
            start = end;
        }
    }

    /**
     * Greedily packs sentences into groups. The first group may use the full {@code maxChars}; later
     * groups reserve room for the overlap prefix that will be prepended.
     */
    private static List<List<String>> pack(List<String> units, ChunkConfig cfg) {
        int reserve = (int) Math.round(cfg.maxChars() * cfg.overlapRatio());
        if (reserve >= cfg.maxChars()) {
            reserve = 0;
        }
        int budget = cfg.maxChars();
        List<List<String>> groups = new ArrayList<>();
        int index = 0;
        while (index < units.size()) {
            List<String> group = new ArrayList<>();
            int used = 0;
            while (index < units.size()) {
                String unit = units.get(index);
                int length = codePointLength(unit);
                if (used + length <= budget) {
                    group.add(unit);
                    used += length;
                    index++;
                } else {
                    break;
                }
            }
            if (group.isEmpty()) {
                // A single sentence longer than the reserved budget; it still fits in maxChars.
                group.add(units.get(index));
                index++;
            }
            groups.add(group);
            budget = cfg.maxChars() - reserve;
            if (budget < 1) {
                budget = cfg.maxChars();
            }
        }
        return groups;
    }

    /**
     * Prevents a section from ending with a chunk shorter than {@code minChars}: merge it into the
     * previous group when that stays within {@code maxChars}, otherwise move sentences from the
     * previous group until the last one is big enough.
     */
    private static void rebalanceTail(List<List<String>> groups, ChunkConfig cfg) {
        while (groups.size() > 1) {
            List<String> last = groups.get(groups.size() - 1);
            int lastLength = groupLength(last);
            if (lastLength >= cfg.minChars()) {
                break;
            }
            List<String> previous = groups.get(groups.size() - 2);
            int previousLength = groupLength(previous);
            if (previousLength + lastLength <= cfg.maxChars()) {
                previous.addAll(last);
                groups.remove(groups.size() - 1);
                continue;
            }
            if (previous.size() > 1) {
                String moved = previous.get(previous.size() - 1);
                int movedLength = codePointLength(moved);
                if (previousLength - movedLength >= cfg.minChars()) {
                    previous.remove(previous.size() - 1);
                    last.add(0, moved);
                    continue;
                }
            }
            break;
        }
    }

    /** Prepends a code-point suffix of each chunk to the next chunk, bounded by {@code maxChars}. */
    private static void applyOverlap(List<String> contents, ChunkConfig cfg) {
        for (int i = 1; i < contents.size(); i++) {
            String previous = contents.get(i - 1);
            String current = contents.get(i);
            int previousLength = codePointLength(previous);
            int currentLength = codePointLength(current);

            int overlap = (int) Math.round(previousLength * cfg.overlapRatio());
            overlap = Math.min(overlap, cfg.maxChars() - currentLength);
            overlap = Math.min(overlap, previousLength);
            if (overlap <= 0) {
                continue;
            }
            contents.set(i, codePointSuffix(previous, overlap) + current);
        }
    }

    // ---------------------------------------------------------------- code point helpers

    static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    static String codePointSuffix(String value, int length) {
        int total = codePointLength(value);
        int take = Math.min(length, total);
        int start = value.offsetByCodePoints(value.length(), -take);
        return value.substring(start);
    }

    private static int groupLength(List<String> group) {
        int length = 0;
        for (String unit : group) {
            length += codePointLength(unit);
        }
        return length;
    }

    // ---------------------------------------------------------------- records

    private record Block(String headingPath, boolean atomic, String text) {
    }

    private record Piece(String headingPath, String text) {
    }
}
