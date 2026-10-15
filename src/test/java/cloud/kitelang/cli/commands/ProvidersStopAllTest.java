package cloud.kitelang.cli.commands;

import cloud.kitelang.packages.process.RunDir;
import cloud.kitelang.proto.v1.HealthCheck;
import cloud.kitelang.proto.v1.ProviderGrpc;
import cloud.kitelang.proto.v1.StopProvider;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code kite providers stop --all} finds running providers through {@link RunDir}, the
 * directory the engine records them in (kitecorp/kite#85). It used to list the
 * {@code .port} files of a hard-coded {@code ~/.kite/run} itself and take each file name
 * as the provider's id, but RunDir writes an id like {@code tf/random@3.9.1} (a Terraform
 * provider loaded through the bridge) as {@code tf%2Frandom@3.9.1}: the command looked for
 * a provider by that name, found none, and left the bridge running.
 *
 * <p>The provider here is an in-test gRPC server recorded in the run directory the build
 * gives every test JVM ({@link RunDir#DIR_PROPERTY}), owned by a live process.</p>
 */
class ProvidersStopAllTest {

    private static final String BRIDGED_ID = "tf/random@3.9.1";

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final RunDir runDir = RunDir.defaultDir();
    private Server provider;
    private Process owner;

    @BeforeEach
    void recordARunningBridgedProvider() throws IOException {
        provider = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0))
                .addService(new RecordingProvider())
                .build()
                .start();
        owner = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();
        runDir.record(BRIDGED_ID, provider.getPort(), owner.toHandle(), "test-cookie");
    }

    @AfterEach
    void stopEverything() {
        runDir.cleanup(BRIDGED_ID);
        owner.destroyForcibly();
        provider.shutdownNow();
    }

    @Test
    void stopAll_stopsABridgedProviderAndRemovesItsRunFiles() {
        var exitCode = new CommandLine(new ProvidersCommand.StopCommand()).execute("--all");

        assertEquals(0, exitCode);
        assertEquals(List.of("HealthCheck", "StopProvider"), calls);
        assertEquals(List.of(), runDir.recordedIds());
    }

    /** Answers like a healthy provider and records which calls it received. */
    private class RecordingProvider extends ProviderGrpc.ProviderImplBase {
        @Override
        public void healthCheck(HealthCheck.Request request, StreamObserver<HealthCheck.Response> response) {
            calls.add("HealthCheck");
            response.onNext(HealthCheck.Response.newBuilder().setHealthy(true).build());
            response.onCompleted();
        }

        @Override
        public void stopProvider(StopProvider.Request request, StreamObserver<StopProvider.Response> response) {
            calls.add("StopProvider");
            response.onNext(StopProvider.Response.getDefaultInstance());
            response.onCompleted();
        }
    }
}
