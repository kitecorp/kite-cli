package cloud.kitelang.cli;

import java.util.Arrays;
import java.util.List;

/**
 * Reads CLI output the way a user sees it on screen.
 */
final class TerminalText {

    private TerminalText() {
    }

    /**
     * Drops ANSI color codes, so assertions read the text a user sees.
     *
     * @param text output that may contain ANSI SGR sequences
     * @return the same text without them
     */
    static String withoutAnsi(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    /**
     * A help header ends with the build version, which changes every release. Masking it keeps the
     * rest of the header a literal.
     *
     * @param header the first line of a help page, without ANSI codes
     * @return the same line with the version in its closing parentheses replaced by {@code <version>}
     */
    static String withVersionMasked(String header) {
        return header.replaceFirst("\\([^()]+\\)$", "(<version>)");
    }

    /**
     * Splits a page at its blank lines into blocks of lines, in order, without ANSI codes. The split
     * keeps empty pieces, so a doubled blank line shows up as a blank line at the start of a block.
     *
     * @param page printed text, such as a help page
     * @return the page's blocks
     */
    static List<List<String>> blocks(String page) {
        return Arrays.stream(withoutAnsi(page).split("\\R\\R", -1))
                .map(block -> block.lines().toList())
                .toList();
    }
}
