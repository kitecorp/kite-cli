package cloud.kitelang.cli.interactive;

import cloud.kitelang.cli.console.Console;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether {@code kite apply} or {@code kite destroy} may change infrastructure. Both commands ask
 * this one class, and it checks in this order:
 * <ol>
 *   <li>the flag: {@code --yes} approves the run, {@code --no-auto-approve} makes it ask;</li>
 *   <li>{@value #AUTO_APPROVE_VARIABLE}: {@code 1} or {@code true} approves a run that gave neither flag,
 *       for CI (kitecorp/kite-cli#27);</li>
 *   <li>the terminal: with none (CI, a cron job, input piped into kite) there is no one to ask, so the run
 *       stops before it changes anything and says which flag approves it (kitecorp/kite-cli#23);</li>
 *   <li>the prompt: yes or no, or for a destroy of an environment that kitefile.yml marks
 *       {@code protected}, the environment's typed name (kitecorp/kite-cli#28).</li>
 * </ol>
 */
public final class Approval {

    /** Approves apply and destroy that give no approval flag, when set to one of {@link #APPROVING_VALUES}. */
    public static final String AUTO_APPROVE_VARIABLE = "KITE_AUTO_APPROVE";

    /** The only values that approve. Anything else, "TRUE" and "yes" included, leaves approval off. */
    private static final Set<String> APPROVING_VALUES = Set.of("1", "true");

    /** Asks the user on the terminal. Tests answer through it without one. */
    interface Prompt {

        /** @return the answer to a yes-or-no question, no when the user just presses enter */
        boolean confirm(String question) throws IOException;

        /** @return the text the user types, empty when they type nothing */
        String answer(String question) throws IOException;
    }

    private static final Prompt TERMINAL = new Prompt() {
        @Override
        public boolean confirm(String question) throws IOException {
            try (var prompt = InteractivePrompt.create()) {
                return prompt != null && prompt.confirm(question, false);
            }
        }

        @Override
        public String answer(String question) throws IOException {
            try (var prompt = InteractivePrompt.create()) {
                return prompt == null ? "" : Objects.requireNonNullElse(prompt.input(question, null), "");
            }
        }
    };

    /** What the checks before the prompt decided. */
    private enum Decision {
        APPROVED_BY_FLAG, APPROVED_BY_VARIABLE, NO_TERMINAL, ASK
    }

    private final Decision decision;
    private final String variable;
    private final String verb;
    private final Prompt prompt;

    private Approval(Decision decision, String variable, String verb, Prompt prompt) {
        this.decision = decision;
        this.variable = variable;
        this.verb = verb;
        this.prompt = prompt;
    }

    /**
     * The approval of one apply or destroy run, from its flag, this process's {@value #AUTO_APPROVE_VARIABLE}
     * and its terminal.
     *
     * @param flag true for {@code --yes}, false for {@code --no-auto-approve}, null when neither was given
     * @param verb what the run does, for its messages: {@code apply} or {@code destroy}
     * @return the decision, to check before the engine starts and to ask with later
     */
    public static Approval forRun(Boolean flag, String verb) {
        return decide(flag, System.getenv(AUTO_APPROVE_VARIABLE), InteractivePrompt.isInteractive(), verb, TERMINAL);
    }

    /**
     * The decision for a flag, a value of {@value #AUTO_APPROVE_VARIABLE} and a terminal or none.
     *
     * @param flag     as for {@link #forRun}
     * @param variable the value of {@value #AUTO_APPROVE_VARIABLE}, or null when it is not set
     * @param terminal whether there is a terminal to ask on
     * @param verb     as for {@link #forRun}
     * @param prompt   what asks the user, when the decision is to ask
     */
    static Approval decide(Boolean flag, String variable, boolean terminal, String verb, Prompt prompt) {
        Decision decision;
        if (Boolean.TRUE.equals(flag)) {
            decision = Decision.APPROVED_BY_FLAG;
        } else if (flag == null && variable != null && APPROVING_VALUES.contains(variable)) {
            decision = Decision.APPROVED_BY_VARIABLE;
        } else if (!terminal) {
            decision = Decision.NO_TERMINAL;
        } else {
            decision = Decision.ASK;
        }
        return new Approval(decision, variable, verb, prompt);
    }

    /**
     * Prints why the run stops or goes ahead without asking. Call it before the engine starts, so a refused
     * run never touches state or the cloud.
     *
     * @param err where the messages go
     * @return true when the run must stop, because nothing approved it and there is no terminal to ask on
     */
    public boolean refused(PrintWriter err) {
        if (decision == Decision.NO_TERMINAL) {
            printLine(err, Console.errorLine("No terminal to ask whether to " + verb + ", so nothing was changed. Add --yes to "
                    + verb + " without asking."));
            return true;
        }
        if (decision == Decision.APPROVED_BY_VARIABLE) {
            // One line, so a CI log shows why nothing was asked
            printLine(err, AUTO_APPROVE_VARIABLE + " is set to " + variable + ", so " + verb + " goes ahead without asking.");
        }
        return false;
    }

    /** Prints the line at once, so it comes before anything the run logs after it. */
    private static void printLine(PrintWriter err, String line) {
        err.println(line);
        err.flush();
    }

    /**
     * @return true when the run is to ask the user before it changes anything
     */
    public boolean asks() {
        return decision == Decision.ASK;
    }

    /**
     * Whether the run may go ahead: yes when a flag or {@value #AUTO_APPROVE_VARIABLE} approved it, otherwise
     * the user's answer to the question. With no terminal it may not.
     *
     * @param question what to ask, answered yes or no (no when the user just presses enter)
     * @return true when the run may change infrastructure
     * @throws IOException when the answer cannot be read
     */
    public boolean confirmed(String question) throws IOException {
        return switch (decision) {
            case APPROVED_BY_FLAG, APPROVED_BY_VARIABLE -> true;
            case NO_TERMINAL -> false;
            case ASK -> prompt.confirm(question);
        };
    }

    /**
     * Whether a destroy may go ahead: yes when a flag or {@value #AUTO_APPROVE_VARIABLE} approved it. Otherwise
     * an environment that kitefile.yml marks {@code protected} needs its name typed, and any other answer
     * cancels (kitecorp/kite-cli#28); any other environment needs a yes.
     *
     * @param environment          the environment to destroy
     * @param protectedEnvironment whether kitefile.yml marks it {@code protected: true}
     * @return true when the run may destroy the environment's resources
     * @throws IOException when the answer cannot be read
     */
    public boolean confirmedDestroy(String environment, boolean protectedEnvironment) throws IOException {
        if (decision != Decision.ASK || !protectedEnvironment) {
            return confirmed("Confirm destruction?");
        }
        return environment.equals(prompt.answer(environment + " is a protected environment. Type its name to destroy it:"));
    }
}
