package cloud.kitelang.cli.commands;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoctorCommandTest {

    @Test
    void java25_passes() {
        assertEquals(new DoctorCommand.JavaCheck(true, "Java 25 (used to run providers)"),
                DoctorCommand.checkJava(OptionalInt.of(25)));
    }

    @Test
    void java21_failsNamingWhatProvidersNeed() {
        assertEquals(new DoctorCommand.JavaCheck(false,
                        "Java 21 found on JAVA_HOME or PATH; providers need Java 25 or newer"),
                DoctorCommand.checkJava(OptionalInt.of(21)));
    }

    @Test
    void noJava_fails() {
        assertEquals(new DoctorCommand.JavaCheck(false,
                        "No java found on JAVA_HOME or PATH; providers need Java 25 or newer"),
                DoctorCommand.checkJava(OptionalInt.empty()));
    }
}
