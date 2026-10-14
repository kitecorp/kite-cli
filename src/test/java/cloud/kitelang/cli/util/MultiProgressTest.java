package cloud.kitelang.cli.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiProgressTest {

    private static final String UP_3 = "\u001B[3A";
    private static final String CLEAR = "\r\u001B[2K";

    @Test
    void lines_showEachDownloadsOwnProgress() {
        var progress = new MultiProgress(List.of("aws", "gcp", "azure"), text -> { }, true);

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
        var progress = new MultiProgress(List.of("aws", "gcp", "azure"), out::append, true);

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
        var progress = new MultiProgress(List.of("aws", "gcp"), out::append, false);

        progress.listener("aws").onProgress(512, 1024, 50);
        progress.done("aws");
        progress.failed("gcp", "HTTP 404");

        assertEquals("  aws  [████████████████] done ✓\n  gcp  failed ✗ HTTP 404\n", out.toString());
    }
}
