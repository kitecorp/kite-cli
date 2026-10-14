package cloud.kitelang.cli.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiProgressTest {

    private static final String UP_3 = "\u001B[3A";
    private static final String CLEAR = "\r\u001B[2K";

    @Test
    void lines_showEachDownloadsOwnProgress() {
        var progress = new MultiProgress(List.of("aws", "gcp", "azure"), text -> { }, true, 120, 40);

        progress.listener("aws").onProgress(12_700_000, 19_800_000, 64);
        progress.done("gcp");
        progress.listener("azure").onProgress(3_145_728, 17_000_000, 18);

        assertEquals(List.of(
                "  aws    [██████████░░░░░░]  64%  12.1 MB",
                "  gcp    [████████████████] done ✓",
                "  azure  [██░░░░░░░░░░░░░░]  18%   3.0 MB"), progress.lines());
    }

    @Test
    void live_redrawsEveryRowInPlace() {
        var out = new StringBuilder();
        var progress = new MultiProgress(List.of("aws", "gcp", "azure"), out::append, true, 120, 40);

        progress.listener("aws").onProgress(512, 1024, 50);
        out.setLength(0);
        progress.failed("gcp", "HTTP 404");

        assertEquals(UP_3
                + CLEAR + "  aws    [████████░░░░░░░░]  50%    512 B\n"
                + CLEAR + "  gcp    failed ✗ HTTP 404\n"
                + CLEAR + "  azure  [░░░░░░░░░░░░░░░░]   0%\n", out.toString());
    }

    @Test
    void notLive_printsOnlyEachFinishedRowOnce() {
        var out = new StringBuilder();
        var progress = new MultiProgress(List.of("aws", "gcp"), out::append, false, 120, 40);

        progress.listener("aws").onProgress(512, 1024, 50);
        progress.done("aws");
        progress.failed("gcp", "HTTP 404");

        assertEquals("  aws  [████████████████] done ✓\n  gcp  failed ✗ HTTP 404\n", out.toString());
    }

    @Test
    void live_cutsALineToTheTerminalWidthSoItNeverWraps() {
        var progress = new MultiProgress(List.of("aws"), text -> { }, true, 40, 40);

        progress.installListener("aws").failed(new Exception("Failed to install aws from git: github.com/kitecorp/kite-providers/aws"));

        // 40 columns: 39 visible characters, the last one the ellipsis
        assertEquals(List.of("  aws  failed ✗ Failed to install aws …"), progress.lines());
    }

    @Test
    void moreRowsThanTheTerminalIsTall_printsOnlyFinalLines() {
        var out = new StringBuilder();
        var progress = new MultiProgress(List.of("aws", "gcp", "azure"), out::append, true, 120, 3);

        progress.listener("aws").onProgress(512, 1024, 50);

        assertEquals("", out.toString());
    }

    @Test
    void failures_keepTheWholeReasonOnOneLine() {
        var progress = new MultiProgress(List.of("aws", "gcp"), text -> { }, true, 40, 40);

        progress.installListener("aws").installed(java.nio.file.Path.of("/providers/aws/v0.2.0"));
        progress.installListener("gcp").failed(new Exception("GitHub API returned 403\nAPI rate limit exceeded"));

        assertEquals(List.of("gcp: GitHub API returned 403 API rate limit exceeded"), progress.failures());
        assertEquals("  aws  [████████████████] done ✓", progress.lines().getFirst());
    }
}
