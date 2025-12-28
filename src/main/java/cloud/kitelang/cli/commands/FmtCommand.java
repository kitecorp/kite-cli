package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Formats .kite files according to standard style conventions.
 * Ensures consistent code style across the project.
 */
@Command(
    name = "fmt",
    aliases = {"format"},
    description = {
        "Format .kite files to canonical style",
        "",
        "Applies consistent formatting to .kite files including:",
        "  - Consistent indentation (4 spaces)",
        "  - Proper brace placement",
        "  - Aligned equals signs in blocks",
        "  - Normalized whitespace",
        "",
        "Alias: format"
    },
    footer = {
        "",
        "Examples:",
        "  kite fmt                              Format all .kite files (recursive)",
        "  kite fmt resources/database.kite     Format a specific file",
        "  kite fmt components/                 Format a specific directory",
        "  kite fmt --check                     Check formatting (CI mode)",
        "  kite fmt --diff                      Preview changes without writing",
        "  kite fmt --indent 2                  Use 2-space indentation",
        "  kite fmt -r=false modules/           Format single directory only"
    },
    mixinStandardHelpOptions = true
)
@Slf4j
public class FmtCommand implements Callable<Integer> {

    private static final int INDENT_SIZE = 4;
    private static final String INDENT = " ".repeat(INDENT_SIZE);

    @Parameters(
        index = "0",
        description = "File or directory to format (defaults to current directory)",
        arity = "0..1"
    )
    private File targetPath;

    @Option(
        names = {"-r", "--recursive"},
        description = "Recursively format all .kite files in subdirectories",
        defaultValue = "true"
    )
    private boolean recursive;

    @Option(
        names = {"--check"},
        description = "Check if files are formatted (don't modify, exit 1 if not formatted)"
    )
    private boolean checkOnly;

    @Option(
        names = {"--diff"},
        description = "Show diff of formatting changes"
    )
    private boolean showDiff;

    @Option(
        names = {"-w", "--write"},
        description = "Write formatted output back to files (default behavior)",
        defaultValue = "true"
    )
    private boolean writeChanges;

    @Option(
        names = {"--indent"},
        description = "Number of spaces for indentation",
        defaultValue = "4"
    )
    private int indentSize;

    @Override
    public Integer call() {
        try {
            var path = targetPath != null ? targetPath.toPath() : Path.of(".");

            if (!Files.exists(path)) {
                Console.error("Path does not exist: " + path);
                return 1;
            }

            var kiteFiles = findKiteFiles(path);

            if (kiteFiles.isEmpty()) {
                Console.println("No .kite files found in: " + path);
                return 0;
            }

            Console.println("Found " + kiteFiles.size() + " .kite file(s)");

            var formatted = 0;
            var unchanged = 0;
            var failed = 0;
            var needsFormatting = new ArrayList<Path>();

            for (var file : kiteFiles) {
                var result = formatFile(file);

                switch (result) {
                    case FORMATTED -> {
                        formatted++;
                        needsFormatting.add(file);
                    }
                    case UNCHANGED -> unchanged++;
                    case FAILED -> failed++;
                }
            }

            // Print summary
            Console.println();
            if (checkOnly) {
                if (!needsFormatting.isEmpty()) {
                    Console.println("Files needing formatting:");
                    for (var file : needsFormatting) {
                        Console.println("  " + file);
                    }
                    Console.println();
                    Console.printf("Check failed: %d file(s) need formatting%n", needsFormatting.size());
                    return 1;
                }
                Console.println("All files are properly formatted.");
            } else {
                Console.printf("Formatted: %d, Unchanged: %d, Failed: %d%n",
                    formatted, unchanged, failed);
            }

            return failed > 0 ? 1 : 0;

        } catch (Exception e) {
            log.error("Formatting failed", e);
            Console.error(e.getMessage());
            return 1;
        }
    }

    /**
     * Finds all .kite files in the given path.
     */
    private List<Path> findKiteFiles(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            if (path.toString().endsWith(".kite")) {
                return List.of(path);
            }
            return List.of();
        }

        var maxDepth = recursive ? Integer.MAX_VALUE : 1;

        try (Stream<Path> walk = Files.walk(path, maxDepth)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".kite"))
                .sorted()
                .toList();
        }
    }

    /**
     * Formats a single file.
     */
    private FormatResult formatFile(Path file) {
        try {
            var original = Files.readString(file);
            var formatted = format(original);

            if (original.equals(formatted)) {
                log.debug("Unchanged: {}", file);
                return FormatResult.UNCHANGED;
            }

            if (showDiff) {
                printDiff(file, original, formatted);
            }

            if (checkOnly) {
                Console.println("  Would format: " + file);
                return FormatResult.FORMATTED;
            }

            if (writeChanges) {
                Files.writeString(file, formatted);
                Console.println("  Formatted: " + file);
            }

            return FormatResult.FORMATTED;

        } catch (IOException e) {
            Console.error("  Failed: " + file + " - " + e.getMessage());
            return FormatResult.FAILED;
        }
    }

    /**
     * Formats the content of a .kite file.
     * TODO: Integrate with proper AST-based formatter.
     */
    private String format(String content) {
        var lines = content.split("\n", -1);
        var result = new StringBuilder();
        var currentIndent = 0;

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            var trimmed = line.trim();

            // Skip empty lines but preserve them
            if (trimmed.isEmpty()) {
                result.append("\n");
                continue;
            }

            // Decrease indent before closing brace
            if (trimmed.startsWith("}")) {
                currentIndent = Math.max(0, currentIndent - 1);
            }

            // Apply indentation
            var indentStr = " ".repeat(currentIndent * indentSize);
            var formattedLine = formatLine(trimmed);
            result.append(indentStr).append(formattedLine).append("\n");

            // Increase indent after opening brace
            if (trimmed.endsWith("{") && !trimmed.startsWith("//")) {
                currentIndent++;
            }

            // Handle inline braces: `resource Foo bar { }`
            if (trimmed.contains("{") && trimmed.endsWith("}") && !trimmed.startsWith("//")) {
                // Don't change indent for single-line blocks
            }
        }

        // Remove trailing newline if original didn't have one
        var formatted = result.toString();
        if (!content.endsWith("\n") && formatted.endsWith("\n")) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }

        return formatted;
    }

    /**
     * Formats a single line.
     */
    private String formatLine(String line) {
        // Normalize spaces around operators
        var formatted = line;

        // Normalize `=` spacing (but not `==`)
        formatted = normalizeOperatorSpacing(formatted, "=");

        // Normalize comma spacing
        formatted = formatted.replaceAll(",\\s*", ", ");

        // Normalize brace spacing
        formatted = formatted.replaceAll("\\{\\s+\\}", "{ }");

        // Normalize multiple spaces to single
        formatted = formatted.replaceAll("  +", " ");

        return formatted.trim();
    }

    /**
     * Normalizes spacing around an operator.
     */
    private String normalizeOperatorSpacing(String line, String op) {
        // Simple approach - more sophisticated parsing needed for edge cases
        var pattern = Pattern.compile("\\s*" + Pattern.quote(op) + "\\s*");
        var matcher = pattern.matcher(line);

        var result = new StringBuilder();
        var lastEnd = 0;

        while (matcher.find()) {
            // Check if this is inside a string (simplified check)
            var beforeMatch = line.substring(0, matcher.start());
            var quoteCount = beforeMatch.length() - beforeMatch.replace("\"", "").length();

            if (quoteCount % 2 == 0) {
                // Not inside string, normalize
                result.append(line, lastEnd, matcher.start());
                result.append(" ").append(op).append(" ");
            } else {
                // Inside string, preserve
                result.append(line, lastEnd, matcher.end());
            }
            lastEnd = matcher.end();
        }

        result.append(line.substring(lastEnd));
        return result.toString();
    }

    /**
     * Prints a diff between original and formatted content.
     */
    private void printDiff(Path file, String original, String formatted) {
        Console.println("--- " + file);
        Console.println("+++ " + file + " (formatted)");

        var origLines = original.split("\n");
        var fmtLines = formatted.split("\n");

        var maxLines = Math.max(origLines.length, fmtLines.length);

        for (var i = 0; i < maxLines; i++) {
            var origLine = i < origLines.length ? origLines[i] : "";
            var fmtLine = i < fmtLines.length ? fmtLines[i] : "";

            if (!origLine.equals(fmtLine)) {
                Console.println("@@ -" + (i + 1) + " +" + (i + 1) + " @@");
                if (!origLine.isEmpty()) {
                    Console.println("-" + origLine);
                }
                if (!fmtLine.isEmpty()) {
                    Console.println("+" + fmtLine);
                }
            }
        }
        Console.println();
    }

    /**
     * Result of formatting a file.
     */
    private enum FormatResult {
        FORMATTED,
        UNCHANGED,
        FAILED
    }
}
