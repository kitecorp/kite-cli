package cloud.kitelang.cli.util;

import cloud.kitelang.engine.distribution.DownloadProgress;

/**
 * Simple console progress bar for downloads.
 */
public class ProgressBar implements DownloadProgress {
    private static final int BAR_WIDTH = 30;
    private final String prefix;
    private int lastPercent = -1;

    public ProgressBar(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public void onProgress(long bytesRead, long totalBytes, int percentComplete) {
        if (percentComplete == lastPercent) return;
        lastPercent = percentComplete;

        if (percentComplete >= 0) {
            renderBar(percentComplete, bytesRead, totalBytes);
        } else {
            // Unknown total - just show bytes
            System.out.print("\r" + prefix + " " + formatBytes(bytesRead) + "...");
            System.out.flush();
        }
    }

    private void renderBar(int percent, long bytesRead, long totalBytes) {
        int filled = (percent * BAR_WIDTH) / 100;
        int empty = BAR_WIDTH - filled;

        StringBuilder bar = new StringBuilder();
        bar.append("\r").append(prefix).append(" [");
        bar.append("█".repeat(filled));
        bar.append("░".repeat(empty));
        bar.append("] ");
        bar.append(String.format("%3d%%", percent));
        bar.append(" (").append(formatBytes(bytesRead));
        if (totalBytes > 0) {
            bar.append("/").append(formatBytes(totalBytes));
        }
        bar.append(")");

        System.out.print(bar);
        System.out.flush();
    }

    /**
     * Clear the progress bar line.
     */
    public void clear() {
        System.out.print("\r" + " ".repeat(80) + "\r");
        System.out.flush();
    }

    /**
     * Complete the progress bar with a final message.
     */
    public void complete(String message) {
        clear();
        System.out.println(message);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
