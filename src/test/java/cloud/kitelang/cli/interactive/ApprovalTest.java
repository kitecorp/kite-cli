package cloud.kitelang.cli.interactive;

import cloud.kitelang.cli.console.Console;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The approval decision of apply and destroy, step by step: the flag, then {@code KITE_AUTO_APPROVE},
 * then whether there is a terminal, then the user's answer (kitecorp/kite-cli#23, #27).
 */
class ApprovalTest {

    private static final String QUESTION = "Do you want to perform these actions?";

    private static final String NO_TERMINAL = Console.errorLine(
            "No terminal to ask whether to apply, so nothing was changed. Add --yes to apply without asking.")
            + System.lineSeparator();

    private static final boolean TERMINAL = true;
    private static final boolean WITHOUT_TERMINAL = false;
    private static final boolean YES = true;
    private static final boolean NO = false;

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("decisions")
    void decidesInTheOrderFlagVariableTerminalAnswer(String situation, Boolean flag, String variable,
                                                     boolean terminal, boolean answer, Outcome expected) throws IOException {
        assertEquals(expected, run(flag, variable, terminal, answer));
    }

    static Stream<Arguments> decisions() {
        return Stream.of(
                Arguments.of("--yes approves without asking, whatever the answer would be",
                        true, null, TERMINAL, NO, approvedWithoutAsking("")),
                Arguments.of("--yes wins over a variable that does not approve, and needs no terminal",
                        true, "0", WITHOUT_TERMINAL, NO, approvedWithoutAsking("")),
                Arguments.of("KITE_AUTO_APPROVE=1 approves without asking, and says so",
                        null, "1", WITHOUT_TERMINAL, NO, approvedWithoutAsking(variableLine("1"))),
                Arguments.of("KITE_AUTO_APPROVE=true approves without asking, and says so",
                        null, "true", WITHOUT_TERMINAL, NO, approvedWithoutAsking(variableLine("true"))),
                Arguments.of("--no-auto-approve asks although the variable approves",
                        false, "1", TERMINAL, YES, asked(YES)),
                Arguments.of("--no-auto-approve with no terminal stops, although the variable approves",
                        false, "1", WITHOUT_TERMINAL, YES, stoppedWithoutTerminal()),
                Arguments.of("no flag and no variable asks, and goes ahead on yes",
                        null, null, TERMINAL, YES, asked(YES)),
                Arguments.of("no flag and no variable asks, and stops on no",
                        null, null, TERMINAL, NO, asked(NO)),
                Arguments.of("no flag and no variable with no terminal stops",
                        null, null, WITHOUT_TERMINAL, YES, stoppedWithoutTerminal()));
    }

    @ParameterizedTest(name = "KITE_AUTO_APPROVE=\"{0}\"")
    @ValueSource(strings = {"0", "false", "yes", "TRUE", " 1", ""})
    void anyOtherValueOfTheVariableLeavesApprovalOff(String value) throws IOException {
        assertEquals(stoppedWithoutTerminal(), run(null, value, WITHOUT_TERMINAL, YES));
    }

    /** What a run sees from one decision: whether it stops, what it printed, whether it goes ahead, what it asked. */
    record Outcome(boolean stops, String printed, boolean goesAhead, List<String> asked) {
    }

    private static Outcome approvedWithoutAsking(String printed) {
        return new Outcome(false, printed, true, List.of());
    }

    private static Outcome asked(boolean answer) {
        return new Outcome(false, "", answer, List.of(QUESTION));
    }

    private static Outcome stoppedWithoutTerminal() {
        return new Outcome(true, NO_TERMINAL, false, List.of());
    }

    private static String variableLine(String value) {
        return "KITE_AUTO_APPROVE is set to " + value + ", so apply goes ahead without asking." + System.lineSeparator();
    }

    /** Runs the decision the way apply does: the check before the engine, then the question if it still stands. */
    private static Outcome run(Boolean flag, String variable, boolean terminal, boolean answer) throws IOException {
        var prompt = new ScriptedPrompt(answer);
        var printed = new StringWriter();
        var approval = Approval.decide(flag, variable, terminal, "apply", prompt);

        var stops = approval.refused(new PrintWriter(printed));
        var goesAhead = !stops && approval.confirmed(QUESTION);

        return new Outcome(stops, printed.toString(), goesAhead, prompt.asked);
    }

    /** Answers every question the same way, with no terminal, and keeps the questions asked. */
    private static final class ScriptedPrompt implements Approval.Prompt {

        private final boolean answer;
        private final List<String> asked = new ArrayList<>();

        private ScriptedPrompt(boolean answer) {
            this.answer = answer;
        }

        @Override
        public boolean confirm(String question) {
            asked.add(question);
            return answer;
        }
    }
}
