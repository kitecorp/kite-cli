package cloud.kitelang.cli.util;

import cloud.kitelang.packages.install.DownloadProgress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One progress line per download, for installs that run in parallel. On an
 * interactive terminal every line is redrawn in place after each update; on one
 * that cannot move the cursor (a dumb terminal, output piped to a file) only each
 * row's final line is printed, once.
 *
 * <p>Downloads report from their own threads, so every update is synchronized.</p>
 */
public final class MultiProgress {

    private static final int BAR_WIDTH = 16;
    private static final String CURSOR_UP = "\u001B[%dA";
    private static final String CLEAR_LINE = "\r\u001B[2K";

    private final Consumer<String> out;
    private final boolean live;
    private final int nameWidth;
    private final Map<String, String> rows = new LinkedHashMap<>();
    private boolean drawn;

    /**
     * @param out  receives raw terminal text; in the CLI this is {@code Console::print}
     * @param live whether the terminal can move the cursor ({@code Console.isInteractive()})
     */
    public MultiProgress(List<String> names, Consumer<String> out, boolean live) {
        this.out = out;
        this.live = live;
        this.nameWidth = names.stream().mapToInt(String::length).max().orElse(0);
        for (var name : names) {
            rows.put(name, prefix(name) + "[" + "░".repeat(BAR_WIDTH) + "]   0%");
        }
    }

    /** The listener for one download; it updates only that download's line. */
    public DownloadProgress listener(String name) {
        return (bytesRead, totalBytes, percent) -> update(name, progressLine(name, bytesRead, percent));
    }

    public synchronized void done(String name) {
        finish(name, prefix(name) + "[" + "█".repeat(BAR_WIDTH) + "] done ✓");
    }

    public synchronized void failed(String name, String reason) {
        finish(name, prefix(name) + "failed ✗ " + reason);
    }

    /** The current text of every line, in the order the names were given. */
    synchronized List<String> lines() {
        return List.copyOf(rows.values());
    }

    private synchronized void update(String name, String line) {
        rows.put(name, line);
        if (live) {
            redraw();
        }
    }

    private void finish(String name, String line) {
        rows.put(name, line);
        if (live) {
            redraw();
        } else {
            out.accept(line + "\n");
        }
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
