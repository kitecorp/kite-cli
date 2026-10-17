package cloud.kitelang.cli;

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
     * The root help header ends with the build version, which changes every release. Masking it keeps
     * the rest of the header a literal.
     *
     * @param header the first line of the root help, without ANSI codes
     * @return the same line with the version in its closing parentheses replaced by {@code <version>}
     */
    static String withVersionMasked(String header) {
        return header.replaceFirst("\\([^()]+\\)$", "(<version>)");
    }
}
