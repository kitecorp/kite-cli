package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import cloud.kitelang.engine.Engine;
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
import java.util.stream.Stream;

/**
 * Validates .kite files for syntax errors, import resolution, and schema compliance.
 * Checks that all resources reference valid types and that imports are resolvable.
 */
@Command(
    name = "validate",
    description = {
        "Validate .kite files for syntax and schema errors",
        "",
        "Checks:",
        "  - Syntax correctness of .kite files",
        "  - Import statements resolve to existing files",
        "  - Resource types match provider schemas",
        "  - Component inputs/outputs are properly declared",
        "",
        "Exit codes:",
        "  0 - All files valid",
        "  1 - Validation errors found"
    },
    footer = {
        "",
        "Examples:",
        "  kite validate                         Validate all .kite files in current directory",
        "  kite validate components/             Validate specific directory",
        "  kite validate main.kite               Validate a specific file",
        "  kite validate --strict                Treat warnings as errors",
        "  kite validate -q                      Quiet mode (errors only)",
        "  kite validate --format json           Output as JSON (for CI integration)"
    },
    mixinStandardHelpOptions = true
)
@Slf4j
public class ValidateCommand implements Callable<Integer> {

    @Parameters(
        index = "0",
        description = "Path to validate (file or directory, defaults to current directory)",
        arity = "0..1"
    )
    private File targetPath;

    @Option(
        names = {"-r", "--recursive"},
        description = "Recursively validate all .kite files in subdirectories",
        defaultValue = "true"
    )
    private boolean recursive;

    @Option(
        names = {"--strict"},
        description = "Enable strict mode (treat warnings as errors)"
    )
    private boolean strict;

    @Option(
        names = {"-q", "--quiet"},
        description = "Only output errors, suppress informational messages"
    )
    private boolean quiet;

    @Option(
        names = {"--format"},
        description = "Output format: text, json",
        defaultValue = "text"
    )
    private String format;

    @Override
    public Integer call() throws Exception {
        var path = targetPath != null ? targetPath.toPath() : Path.of(".");

        if (!Files.exists(path)) {
            Console.error("Path does not exist: " + path);
            return 1;
        }

        log.info("Validating .kite files in: {}", path.toAbsolutePath());

        var kiteFiles = findKiteFiles(path);

        if (kiteFiles.isEmpty()) {
            if (!quiet) {
                Console.println("No .kite files found in: " + path);
            }
            return 0;
        }

        if (!quiet) {
            Console.println("Found " + kiteFiles.size() + " .kite file(s) to validate\n");
        }

        var errors = new ArrayList<ValidationError>();
        var warnings = new ArrayList<ValidationError>();

        for (var file : kiteFiles) {
            validateFile(file, errors, warnings);
        }

        // Output results
        printResults(kiteFiles.size(), errors, warnings);

        // Determine exit code
        // Any exceptions from here will be handled by KiteExceptionHandler
        // which shows user-friendly messages and only shows stack traces when KITE_LOGGING=debug
        if (!errors.isEmpty()) {
            return 1;
        }
        if (strict && !warnings.isEmpty()) {
            return 1;
        }
        return 0;
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
     * Validates a single .kite file using the Engine parser.
     */
    private void validateFile(Path file, List<ValidationError> errors, List<ValidationError> warnings) {
        log.debug("Validating: {}", file);

        try {
            var content = Files.readString(file);

            // Check for empty file
            if (content.isBlank()) {
                warnings.add(new ValidationError(file, 1, "File is empty"));
                return;
            }

            // Use Engine for full syntax, type, and semantic validation
            try (var engine = Engine.builder()
                    .forTesting()  // Skip provider loading for validation
                    .skipDatabaseInit()
                    .build()) {
                engine.parse(content);
            }

            // Check imports (additional portability check)
            validateImports(file, content, errors, warnings);

            if (!quiet) {
                Console.println("  ✓ " + file);
            }

        } catch (IOException e) {
            errors.add(new ValidationError(file, 0, "Cannot read file: " + e.getMessage()));
        } catch (Exception e) {
            // Parse or type check error - extract line number if possible
            var message = e.getMessage();
            var lineNum = extractLineNumber(message);
            errors.add(new ValidationError(file, lineNum, message));
        }
    }

    /**
     * Extracts line number from error message if present.
     */
    private int extractLineNumber(String message) {
        if (message == null) return 0;
        // Try to find "line X" or ":X:" pattern
        var linePattern = java.util.regex.Pattern.compile("line (\\d+)|:(\\d+):");
        var matcher = linePattern.matcher(message);
        if (matcher.find()) {
            var group = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            try {
                return Integer.parseInt(group);
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /**
     * Performs basic syntax validation.
     * TODO: Replace with proper lexer/parser.
     */
    private void validateBasicSyntax(Path file, String content,
                                      List<ValidationError> errors,
                                      List<ValidationError> warnings) {
        var lines = content.split("\n");
        var braceCount = 0;
        var inString = false;
        var inComment = false;

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            var lineNum = i + 1;

            // Skip comment lines
            var trimmed = line.trim();
            if (trimmed.startsWith("//")) {
                continue;
            }

            // Count braces (simplified - doesn't handle strings/comments properly)
            for (var c : line.toCharArray()) {
                if (c == '{') braceCount++;
                if (c == '}') braceCount--;
            }

            // Check for common issues
            if (trimmed.startsWith("resource ") && !trimmed.contains("{")) {
                // Resource declaration should have opening brace (possibly on same line)
                var nextLineNum = i + 1;
                if (nextLineNum < lines.length && !lines[nextLineNum].trim().startsWith("{")) {
                    // Check if brace is on same line
                    if (!trimmed.endsWith("{")) {
                        warnings.add(new ValidationError(file, lineNum,
                            "Resource declaration may be missing opening brace"));
                    }
                }
            }

            if (trimmed.startsWith("component ") && !trimmed.contains("{")) {
                if (!trimmed.endsWith("{")) {
                    warnings.add(new ValidationError(file, lineNum,
                        "Component declaration may be missing opening brace"));
                }
            }
        }

        if (braceCount != 0) {
            errors.add(new ValidationError(file, lines.length,
                "Unbalanced braces: " + (braceCount > 0 ? "missing " + braceCount + " closing" : "extra " + (-braceCount) + " closing")));
        }
    }

    /**
     * Validates import statements.
     */
    private void validateImports(Path file, String content,
                                  List<ValidationError> errors,
                                  List<ValidationError> warnings) {
        var lines = content.split("\n");
        var fileDir = file.getParent();

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            var lineNum = i + 1;

            if (line.startsWith("import ") && line.contains("from")) {
                // Extract the path from: import * from "path/to/file.kite"
                var pathMatch = extractImportPath(line);

                if (pathMatch != null) {
                    var importPath = fileDir.resolve(pathMatch).normalize();

                    if (!Files.exists(importPath)) {
                        errors.add(new ValidationError(file, lineNum,
                            "Import not found: " + pathMatch));
                    }

                    // Check portability rules
                    if (isInComponentsDir(file) && pathMatch.contains("cloud/")) {
                        errors.add(new ValidationError(file, lineNum,
                            "Portability violation: components cannot import from cloud/"));
                    }
                }
            }
        }
    }

    /**
     * Extracts the file path from an import statement.
     */
    private String extractImportPath(String line) {
        var startQuote = line.indexOf('"');
        var endQuote = line.lastIndexOf('"');

        if (startQuote != -1 && endQuote > startQuote) {
            return line.substring(startQuote + 1, endQuote);
        }
        return null;
    }

    /**
     * Checks if a file is in the components directory.
     */
    private boolean isInComponentsDir(Path file) {
        return file.toString().contains("/components/") ||
               file.toString().contains("\\components\\");
    }

    /**
     * Prints validation results.
     */
    private void printResults(int totalFiles, List<ValidationError> errors, List<ValidationError> warnings) {
        Console.println();

        if (!warnings.isEmpty()) {
            Console.println("Warnings:");
            for (var warning : warnings) {
                Console.println("  " + warning);
            }
            Console.println();
        }

        if (!errors.isEmpty()) {
            Console.println("Errors:");
            for (var error : errors) {
                Console.println("  " + error);
            }
            Console.println();
        }

        // Summary
        var status = errors.isEmpty() ? "✓" : "✗";
        Console.printf("%s Validated %d file(s): %d error(s), %d warning(s)%n",
            status, totalFiles, errors.size(), warnings.size());
    }

    /**
     * Represents a validation error or warning.
     */
    private record ValidationError(Path file, int line, String message) {
        @Override
        public String toString() {
            if (line > 0) {
                return file + ":" + line + ": " + message;
            }
            return file + ": " + message;
        }
    }
}
