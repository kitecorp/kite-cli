package cloud.kitelang.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The installed {@code kite} binary, run as a child process the way a user runs it, with a throwaway
 * home and an environment the test chooses.
 */
final class KiteBinary {

    /** Built by {@code installDist}; the build passes its location. */
    static final Path KITE = Path.of(System.getProperty("kite.installDir", "build/install/kite"), "bin", "kite");

    /** Longest a single run may take. JVM start-up is about a second; nothing here needs the network. */
    static final Duration RUN_LIMIT = Duration.ofSeconds(60);

    /** No PostgreSQL listens on port 1, so the engine stops at its first step: opening the database. */
    static final String UNREACHABLE_DATABASE = "jdbc:postgresql://127.0.0.1:1/postgres";

    /** State backend settings after the url, for {@link #projectWithUnreachableState}. */
    static final String WITH_PASSWORD = """
            username: postgres
            password: postgres
            """;

    /** As {@link #WITH_PASSWORD}, with no password, which kite warns about before it starts the engine. */
    static final String WITHOUT_PASSWORD = """
            username: postgres
            """;

    private final Path home;

    /** @param home the child's {@code user.home}, so kite never reads or writes the developer's ~/.kite */
    KiteBinary(Path home) {
        this.home = home;
    }

    /** The command that runs kite with these arguments. */
    static List<String> kite(String... args) {
        var command = new ArrayList<>(List.of(KITE.toString()));
        command.addAll(List.of(args));
        return command;
    }

    /**
     * The child's JVM options: its own home, and fixed stream encodings. A pipe takes its encoding from
     * the locale, so fixing stdout's keeps ✓ and … readable on any machine.
     */
    String javaOptions(String stderrEncoding) {
        return "-Duser.home=" + home + " -Dstdout.encoding=UTF-8 -Dstderr.encoding=" + stderrEncoding;
    }

    /**
     * A builder for the command, in an environment that holds only what the test sets, so the
     * developer's TERM, KITE_LOGGING or database settings cannot change what kite prints.
     */
    ProcessBuilder builder(Path workDir, Map<String, String> environmentOverrides, List<String> command) {
        var builder = new ProcessBuilder(command).directory(workDir.toFile());
        var environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv("PATH"));
        environment.put("HOME", home.toString());
        environment.put("TERM", "xterm-256color");
        // The start script runs the JDK this test runs on, which is the project's toolchain
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        environment.put("JAVA_OPTS", javaOptions("UTF-8"));
        environment.putAll(environmentOverrides);
        return builder;
    }

    /**
     * Runs kite with no terminal at all, as CI, a cron job or {@code ... | kite} does: stdin, stdout and
     * stderr are all pipes, and stdin is already at its end.
     */
    Output runWithoutTerminal(Path workDir, String... args) throws Exception {
        return runWithoutTerminal(workDir, Map.of(), args);
    }

    /** As {@link #runWithoutTerminal(Path, String...)}, with these environment variables set too. */
    Output runWithoutTerminal(Path workDir, Map<String, String> environment, String... args) throws Exception {
        var process = builder(workDir, environment, kite(args)).start();
        try {
            process.getOutputStream().close();
            var stdout = drain(process.getInputStream());
            var stderr = drain(process.getErrorStream());
            if (!process.waitFor(RUN_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                fail("kite did not exit within " + RUN_LIMIT);
            }
            return new Output(process.exitValue(), stdout.get(RUN_LIMIT.toMillis(), TimeUnit.MILLISECONDS),
                    stderr.get(RUN_LIMIT.toMillis(), TimeUnit.MILLISECONDS));
        } finally {
            process.destroyForcibly();
        }
    }

    /** What one run with no terminal printed on each stream, and how it exited. */
    record Output(int exitCode, String stdout, String stderr) {
    }

    /**
     * A project whose dev environment has one stack and a state backend that cannot be reached, so a
     * command gets past its own checks and stops the moment the engine opens the database.
     *
     * @param parent      where to create the project directory, {@code piped-project}
     * @param credentials the state backend's YAML lines after its url, unindented
     * @return the project directory, to run kite in
     */
    Path projectWithUnreachableState(Path parent, String credentials) throws IOException {
        var project = Files.createDirectories(parent.resolve("piped-project"));
        Files.writeString(Files.createDirectories(project.resolve("environments/dev")).resolve("main.kite"),
                "var greeting = \"hello\"\n");
        // kite names the project after its working directory
        Files.writeString(Files.createDirectories(home.resolve(".kite")).resolve("config.yml"), """
                projects:
                - name: piped-project
                  environments:
                    dev:
                      state:
                        type: postgresql
                        url: %s
                %s""".formatted(UNREACHABLE_DATABASE, credentials.indent(8)));
        return project;
    }

    /** Reads the stream to its end on its own thread, so a long output cannot fill the pipe and stall kite. */
    static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> readAll(stream), task -> Thread.ofPlatform().daemon().start(task));
    }

    private static String readAll(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
