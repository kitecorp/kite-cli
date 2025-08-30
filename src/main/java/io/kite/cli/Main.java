package io.kite.cli;

import io.kite.engine.Kite;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;

import static picocli.CommandLine.Parameters;

@Command(name = "cio", mixinStandardHelpOptions = true, version = "cio 1.0",
        description = "Prints the checksum (SHA-256 by default) of a file to STDOUT.")
@Log4j2
class Main implements Runnable {

    @Parameters(paramLabel = "SourceFiles", description = "The source file")
    private File[] sourceFiles;

    private final Kite kite;


    @SneakyThrows
    public Main() {
        kite = new Kite();
    }

    @SneakyThrows
    @Override
    public void run() { // your business logic goes here...
        kite.run();
    }

    public static void main(String... args) {
        var commandLine = new CommandLine(new Main());
        commandLine.execute(args);
    }
}