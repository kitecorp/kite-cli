package cloud.kitelang.cli.util;

import cloud.kitelang.cli.console.Console;
import cloud.kitelang.packages.install.DownloadProgress;
import cloud.kitelang.packages.install.InstallListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One progress line per download, for installs that run in parallel. On an
 * interactive terminal every line is redrawn in place after each update. Redrawing
 * moves the cursor up one line per row, so it only works when every row is exactly
 * one screen line: lines are cut to the terminal width, and when there are more rows
 * than the terminal is tall, or the terminal cannot move the cursor (a dumb terminal,
 * output piped to a file), only each row's final line is printed, once.
 *
 * <p>Downloads report from their own threads, so every update is synchronized.</p>
 */
public final class MultiProgress {

    private static final int BAR_WIDTH = 16;
    private static final String CURSOR_UP = "\u001B[%dA";
    private static final String CLEAR_LINE = "\r\u001B[2K";

    private final Consumer<String> out;
    private final boolean live;
    private final int maxLineLength;
    private final int nameWidth;
    private final Map<String, String> rows = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private boolean drawn;

    /**
     * @param out         receives raw terminal text; in the CLI this is {@code Console::print}
     * @param interactive whether the output can move the cursor ({@code Console.isOutputInteractive()})
     * @param width       terminal columns
     * @param height      terminal rows
     */
    public MultiProgress(List<String> names, Consumer<String> out, boolean interactive, int width, int height) {
        this.out = out;
        this.live = interactive && names.size() < height;
        // One column short of the width: a line that fills it exactly wraps on some terminals
        this.maxLineLength = Math.max(10, width - 1);
        this.nameWidth = names.stream().mapToInt(String::length).max().orElse(0);
        for (var name : names) {
            rows.put(name, prefix(name) + "[" + "░".repeat(BAR_WIDTH) + "]   0%");
        }
    }

    /**
     * Progress printed through the Console. It redraws in place only when the output itself is an
     * interactive terminal; piped or redirected, each line is printed once.
     */
    public static MultiProgress onConsole(List<String> names) {
        return new MultiProgress(names, Console::print, Console.isOutputInteractive(), Console.width(), Console.height());
    }

    /** The listener for one download; it updates only that download's line. */
    public DownloadProgress listener(String name) {
        return (bytesRead, totalBytes, percent) -> update(name, progressLine(name, bytesRead, percent));
    }

    public synchronized void done(String name) {
        finish(name, prefix(name) + "[" + "█".repeat(BAR_WIDTH) + "] done ✓");
    }

    public synchronized void failed(String name, String reason) {
        var oneLine = reason == null ? "unknown error" : reason.replaceAll("\\s*\\R\\s*", " ");
        failures.add(name + ": " + oneLine);
        finish(name, prefix(name) + "failed ✗ " + oneLine);
    }

    /** A listener for one package of a parallel install: its progress, then done or failed. */
    public InstallListener installListener(String name) {
        var progress = listener(name);
        return new InstallListener() {
            @Override
            public void onProgress(long bytesRead, long totalBytes, int percentComplete) {
                progress.onProgress(bytesRead, totalBytes, percentComplete);
            }

            @Override
            public void installed(Path packageDir) {
                done(name);
            }

            @Override
            public void failed(Throwable error) {
                MultiProgress.this.failed(name, error.getMessage());
            }
        };
    }

    /** Every failure with its whole reason, for printing once the live lines are finished. */
    public synchronized List<String> failures() {
        return List.copyOf(failures);
    }

    /** The current text of every line, in the order the names were given. */
    synchronized List<String> lines() {
        return List.copyOf(rows.values());
    }

    private synchronized void update(String name, String line) {
        rows.put(name, fit(line));
        if (live) {
            redraw();
        }
    }

    private void finish(String name, String line) {
        var fitted = fit(line);
        rows.put(name, fitted);
        if (live) {
            redraw();
        } else {
            out.accept(fitted + "\n");
        }
    }

    private String fit(String line) {
        return line.length() <= maxLineLength ? line : line.substring(0, maxLineLength - 1) + "…";
    }

    private void redraw() {
        var frame = new StringBuilder();
        if (drawn) {
            frame.append(CURSOR_UP.formatted(rows.size()));
        }
        for (var line : rows.values()) {
            frame.append(CLEAR_LINE).append(line).append('\n');
        }
        drawn = true;
        out.accept(frame.toString());
    }

    private String progressLine(String name, long bytesRead, int percent) {
        if (percent < 0) {
            // The server sent no length: show how much has arrived without a bar
            return prefix(name) + "[" + "░".repeat(BAR_WIDTH) + "]    ?  " + "%7s".formatted(formatBytes(bytesRead));
        }
        int filled = Math.min(BAR_WIDTH, percent * BAR_WIDTH / 100);
        return prefix(name) + "[" + "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled) + "] "
                + String.format(Locale.ROOT, "%3d%%", percent) + "  " + "%7s".formatted(formatBytes(bytesRead));
    }

    private String prefix(String name) {
        return "  " + name + " ".repeat(nameWidth - name.length()) + "  ";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        // Locale.ROOT: the unit labels are English, so the decimal point is too
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
