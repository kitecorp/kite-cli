package cloud.kitelang.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A pseudo-terminal, opened through libc, that a test gives a child process as its terminal.
 *
 * <p>A shell running {@code kite --help | cat} hands kite a pipe on stdout and the user's terminal on
 * stdin and stderr. {@link #start(ProcessBuilder)} reproduces that: the child's stdin and stderr are this
 * terminal and its stdout stays the pipe {@link ProcessBuilder} makes. What the child draws on the
 * terminal is read back with {@link #screen()}, and keys reach it through {@link #type(String)}.</p>
 *
 * <p>Uses the POSIX calls macOS and Linux share. Windows has no such calls, so the class cannot load
 * there.</p>
 */
final class PseudoTerminal implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle POSIX_OPENPT = libc("posix_openpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GRANTPT = libc("grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle UNLOCKPT = libc("unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle PTSNAME = libc("ptsname", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle READ = libc("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle WRITE = libc("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle CLOSE = libc("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    private static final int O_RDWR = 2;
    /** Keeps the terminal from becoming this JVM's controlling terminal. The value differs per kernel. */
    private static final int O_NOCTTY = System.getProperty("os.name").startsWith("Mac") ? 0x20000 : 0x100;
    /** Longer than any device path ptsname returns ({@code /dev/ttys012}, {@code /dev/pts/3}). */
    private static final long DEVICE_NAME_LIMIT = 128;
    private static final int READ_BUFFER_SIZE = 4096;

    private final int master;
    private final Path device;
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();
    private Thread reader;
    /** Set, under the {@code received} lock, once no process holds the terminal open. */
    private boolean closed;

    private PseudoTerminal(int master, Path device) {
        this.master = master;
        this.device = device;
    }

    /**
     * Opens a new pseudo-terminal pair.
     *
     * @return the terminal, ready for {@link #start(ProcessBuilder)}
     * @throws IOException when libc refuses to create or unlock it
     */
    static PseudoTerminal open() throws IOException {
        try {
            var master = (int) POSIX_OPENPT.invokeExact(O_RDWR | O_NOCTTY);
            check(master, "posix_openpt");
            check((int) GRANTPT.invokeExact(master), "grantpt");
            check((int) UNLOCKPT.invokeExact(master), "unlockpt");
            var name = (MemorySegment) PTSNAME.invokeExact(master);
            if (name.equals(MemorySegment.NULL)) {
                throw new IOException("ptsname failed");
            }
            return new PseudoTerminal(master, Path.of(name.reinterpret(DEVICE_NAME_LIMIT).getString(0)));
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not open a pseudo-terminal", e);
        }
    }

    /**
     * Starts the process with this terminal as its stdin and stderr. Its stdout is left as configured,
     * a pipe unless the builder says otherwise.
     *
     * @param builder the command to start
     * @return the started process
     * @throws IOException when the process cannot start
     */
    Process start(ProcessBuilder builder) throws IOException {
        var process = builder
                .redirectInput(device.toFile())
                .redirectError(ProcessBuilder.Redirect.appendTo(device.toFile()))
                .start();
        // Read only once the child holds the terminal open: on Linux, reading a terminal nobody has
        // opened fails at once, which would end the reader before the child prints anything.
        reader = Thread.ofPlatform().daemon().name("pseudo-terminal-reader").start(this::readUntilClosed);
        return process;
    }

    /**
     * Sends keys, as if typed on the terminal's keyboard.
     *
     * @param keys the characters to send, for example {@code "\r"} for Enter
     * @throws IOException when the terminal does not take them all
     */
    void type(String keys) throws IOException {
        var bytes = keys.getBytes(StandardCharsets.UTF_8);
        try (var arena = Arena.ofConfined()) {
            var buffer = arena.allocateFrom(JAVA_BYTE, bytes);
            var written = (long) WRITE.invokeExact(master, buffer, (long) bytes.length);
            if (written != bytes.length) {
                throw new IOException("Typed " + written + " of " + bytes.length + " bytes");
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not type on the pseudo-terminal", e);
        }
    }

    /**
     * Everything drawn on the terminal so far. The terminal turns each line feed into CR LF, as a real
     * one does; this turns them back, so the text compares with what the child printed.
     *
     * @return the received text
     */
    String screen() {
        synchronized (received) {
            return received.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    /**
     * Waits until the screen shows the text, for a prompt that must be drawn before keys are typed.
     *
     * @param text    the text to wait for
     * @param timeout how long to wait
     * @return true once the text is on screen; false when the timeout passed first, or when every
     *         process closed the terminal without showing it
     * @throws InterruptedException when the test is interrupted
     */
    boolean awaitScreen(String text, Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        synchronized (received) {
            while (!screen().contains(text)) {
                var remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0 || closed) {
                    return false;
                }
                received.wait(remaining);
            }
            return true;
        }
    }

    /**
     * Waits until no process holds the terminal open any more, so the screen is complete.
     *
     * @param timeout how long to wait
     * @return true when the reader saw the terminal close
     * @throws InterruptedException when the test is interrupted
     */
    boolean awaitClosed(Duration timeout) throws InterruptedException {
        return reader == null || reader.join(timeout);
    }

    /**
     * Closes the terminal once nothing reads it. While a process still holds it open the reader is
     * blocked in read(2), and closing its descriptor would let the next file this JVM opens take the
     * same number and be read in its place; the descriptor is then left open instead.
     */
    @Override
    public void close() throws IOException {
        if (reader != null && reader.isAlive()) {
            return;
        }
        try {
            check((int) CLOSE.invokeExact(master), "close");
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not close the pseudo-terminal", e);
        }
    }

    private void readUntilClosed() {
        try (var arena = Arena.ofConfined()) {
            var buffer = arena.allocate(READ_BUFFER_SIZE);
            long count;
            // read returns 0 on macOS, and fails with EIO on Linux, once the last process holding the
            // terminal open has closed it
            while ((count = (long) READ.invokeExact(master, buffer, (long) READ_BUFFER_SIZE)) > 0) {
                synchronized (received) {
                    received.writeBytes(buffer.asSlice(0, count).toArray(JAVA_BYTE));
                    received.notifyAll();
                }
            }
        } catch (Throwable e) {
            throw new IllegalStateException("Could not read the pseudo-terminal", e);
        } finally {
            synchronized (received) {
                closed = true;
                received.notifyAll();
            }
        }
    }

    private static void check(int result, String call) throws IOException {
        if (result < 0) {
            throw new IOException(call + " failed");
        }
    }

    private static MethodHandle libc(String name, FunctionDescriptor descriptor) {
        var symbol = LINKER.defaultLookup().find(name)
                .orElseThrow(() -> new IllegalStateException("libc has no " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }
}
