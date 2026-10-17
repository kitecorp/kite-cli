package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.interactive.Approval;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

/**
 * The flags that decide whether apply and destroy ask before they change anything. Both commands mix in
 * this one declaration, so they take the same flags. The flags exclude each other: picocli rejects a run
 * that gives both.
 *
 * <p>The group sits in a mixin, not in each command: picocli's annotation processor cannot build one
 * {@code @ArgGroup} class into two commands.</p>
 */
final class ApprovalFlags {

    @ArgGroup(exclusive = true)
    private Choice choice;

    static final class Choice {

        @Option(
                names = {"-y", "--yes", "--approve", "--auto-approve"},
                description = "Skip interactive approval"
        )
        private boolean approve;

        // Read only by picocli: given alone, it leaves approve false
        @Option(
                names = "--no-auto-approve",
                description = "Ask for approval, even when " + Approval.AUTO_APPROVE_VARIABLE + " is set"
        )
        private boolean ask;
    }

    /**
     * @return true for {@code --yes}, false for {@code --no-auto-approve}, null when neither was given
     */
    Boolean flag() {
        return choice == null ? null : choice.approve;
    }
}
