package cloud.kitelang.cli.util;

import cloud.kitelang.packages.install.DownloadProgress;
import cloud.kitelang.packages.install.PackageSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;

/** Installs several packages at once, each on its own virtual thread with its own progress line. */
public final class ParallelInstall {

    /** Installs one spec, reporting its download to {@code progress}. */
    @FunctionalInterface
    public interface Installer {
        Path install(PackageSpec spec, DownloadProgress progress) throws Exception;
    }

    private ParallelInstall() {
    }

    /**
     * Runs every install to the end, marking each line done or failed as it finishes.
     *
     * @return the names of the specs that failed, in spec order
     */
    public static List<String> run(List<PackageSpec> specs, Installer installer, MultiProgress progress) {
        var failed = Collections.synchronizedSet(new HashSet<String>());
        // close() waits for every install to finish
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var spec : specs) {
                executor.submit(() -> {
                    try {
                        installer.install(spec, progress.listener(spec.getName()));
                        progress.done(spec.getName());
                    } catch (Exception e) {
                        failed.add(spec.getName());
                        progress.failed(spec.getName(), e.getMessage());
                    }
                });
            }
        }
        var inOrder = new ArrayList<String>();
        for (var spec : specs) {
            if (failed.contains(spec.getName())) {
                inOrder.add(spec.getName());
            }
        }
        return inOrder;
    }
}
