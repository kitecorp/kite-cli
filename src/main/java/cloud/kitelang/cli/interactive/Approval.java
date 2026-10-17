package cloud.kitelang.cli.interactive;

import cloud.kitelang.cli.console.Console;

import java.io.PrintWriter;

/**
 * Apply and destroy change infrastructure only once someone approves it, at the prompt or with
 * {@code --yes}. With no terminal (CI, a cron job, input piped into kite) there is no prompt to answer,
 * so such a run stops before it changes anything and says which flag approves it (kitecorp/kite-cli#23).
 */
public final class Approval {

    private Approval() {
    }

    /**
     * Stops a run that needs approval when there is no terminal to ask on. Call it before the engine
     * starts, so a refused run never touches state or the cloud.
     *
     * @param approved whether {@code --yes} was given
     * @param verb     what the run does, for the message: {@code apply} or {@code destroy}
     * @param err      where the refusal is printed
     * @return true when the run must stop; the refusal has then been printed
     */
    public static boolean refusedWithoutTerminal(boolean approved, String verb, PrintWriter err) {
        if (approved || InteractivePrompt.isInteractive()) {
            return false;
        }
        err.println(Console.errorLine("No terminal to ask whether to " + verb + ", so nothing was changed. Add --yes to "
                + verb + " without asking."));
        err.flush();
        return true;
    }
}
