package cloud.kitelang.cli.util;

import cloud.kitelang.packages.install.PackageInstallException;
import cloud.kitelang.packages.install.PackageSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParallelInstallTest {

    @Test
    void run_installsAtTheSameTimeOnVirtualThreadsAndFinishesEveryLine() {
        var specs = List.of(
                PackageSpec.builder().name("aws").version("v0.2.0").build(),
                PackageSpec.builder().name("gcp").version("v0.1.0").build());
        var progress = new MultiProgress(List.of("aws", "gcp"), text -> { }, true);
        // Each install waits until both have started, so a one-at-a-time loop never finishes
        var bothStarted = new CountDownLatch(2);
        var onVirtualThreads = new AtomicBoolean(true);

        var failed = ParallelInstall.run(specs, (spec, listener) -> {
            onVirtualThreads.compareAndSet(true, Thread.currentThread().isVirtual());
            bothStarted.countDown();
            if (!bothStarted.await(5, TimeUnit.SECONDS)) {
                throw new PackageInstallException("installs did not overlap");
            }
            if (spec.getName().equals("gcp")) {
                throw new PackageInstallException("HTTP 404");
            }
            listener.onProgress(1024, 1024, 100);
            return Path.of("/providers/aws/v0.2.0");
        }, progress);

        assertEquals(List.of("gcp"), failed);
        assertEquals(true, onVirtualThreads.get());
        assertEquals(List.of(
                "  aws  [████████████████] done ✓",
                "  gcp  failed ✗ HTTP 404"), progress.lines());
    }
}
