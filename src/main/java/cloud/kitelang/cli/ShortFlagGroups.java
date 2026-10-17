package cloud.kitelang.cli;

import picocli.CommandLine;
import picocli.CommandLine.IParameterPreprocessor;
import picocli.CommandLine.MissingParameterException;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.Serial;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Reads a single-dash word as a group of short flags, as kitecorp/kite-cli#18 defines it: {@code -ye dev}
 * and {@code -ey dev} both mean {@code -y -e dev}.
 *
 * <p>A flag of a group that takes a value takes it from the next word, never from the rest of the group,
 * so {@code -es dev Backend} is {@code -e dev -s Backend}. picocli's own POSIX clustering does the
 * opposite: it gives the rest of the word to the first flag that takes a value, which once read
 * {@code kite apply -yes} as an approved apply to environment "s". kite turns that clustering off and
 * gives every command this preprocessor instead.</p>
 *
 * <p>A word is a group only when each of its letters is a short flag of the command being parsed. Any
 * other word, such as {@code -environment} or {@code -eprod}, is left as typed, and picocli reports it
 * by that word.</p>
 */
final class ShortFlagGroups implements IParameterPreprocessor {

    /** picocli reads every word after this one as a positional parameter. */
    private static final String END_OF_OPTIONS = "--";

    private static final ShortFlagGroups INSTANCE = new ShortFlagGroups();

    private ShortFlagGroups() {
    }

    /**
     * Makes a command and every command below it read groups of short flags. picocli calls a command's
     * preprocessor with the words still to parse when it reaches that command, so each command
     * expands its own words against its own flags.
     *
     * @param command the root of the commands to set up
     */
    static void install(CommandLine command) {
        command.getCommandSpec().preprocessor(INSTANCE);
        command.getSubcommands().values().forEach(ShortFlagGroups::install);
    }

    @Override
    public boolean preprocess(Stack<String> args, CommandSpec command, ArgSpec argSpec, Map<String, Object> info) {
        // The stack holds the next word on top
        var expanded = expand(args.reversed(), command);
        args.clear();
        expanded.reversed().forEach(args::push);
        // picocli parses the expanded words as usual
        return false;
    }

    /**
     * The words of a command, with each group of its short flags written out flag by flag.
     *
     * @param words the words still to parse when picocli reached the command, in the order typed
     * @param command the command being parsed
     * @return the words, each group replaced by its flags, each flag that takes a value followed by its value
     * @throws MissingValueException when a group has more flags that take a value than words after it
     */
    static List<String> expand(List<String> words, CommandSpec command) {
        var remaining = new ArrayDeque<>(words);
        var expanded = new ArrayList<String>();
        while (!remaining.isEmpty()) {
            var word = remaining.pop();
            if (endsTheCommandsOptions(word, command)) {
                expanded.add(word);
                expanded.addAll(remaining);
                break;
            }
            if (isGroup(word, command)) {
                expanded.addAll(flagsOf(word, remaining, command));
            } else {
                expanded.add(word);
                // The word after an option that takes a value is that value, whatever it looks like
                if (takesValue(command.optionsMap().get(word)) && !remaining.isEmpty()) {
                    expanded.add(remaining.pop());
                }
            }
        }
        return expanded;
    }

    /**
     * Whether no later word belongs to this command's options. In a command with subcommands, the first
     * plain word names the subcommand (or is an error picocli reports), and the subcommand expands the
     * words after it itself.
     */
    private static boolean endsTheCommandsOptions(String word, CommandSpec command) {
        return word.equals(END_OF_OPTIONS) || (!word.startsWith("-") && !command.subcommands().isEmpty());
    }

    private static boolean isGroup(String word, CommandSpec command) {
        var singleDashWord = word.length() > 2 && word.startsWith("-") && !word.startsWith("--");
        return singleDashWord
                && !command.optionsMap().containsKey(word)
                && word.substring(1).chars().allMatch(letter -> command.optionsMap().containsKey(flag(letter)));
    }

    /** The flags of a group, each flag that takes a value followed by the next remaining word. */
    private static List<String> flagsOf(String group, Deque<String> remaining, CommandSpec command) {
        var flags = new ArrayList<String>();
        group.substring(1).chars().mapToObj(ShortFlagGroups::flag).forEach(flag -> {
            flags.add(flag);
            var option = command.optionsMap().get(flag);
            if (takesValue(option)) {
                if (remaining.isEmpty()) {
                    throw new MissingValueException(command.commandLine(), option, flag, group);
                }
                flags.add(remaining.pop());
            }
        });
        return flags;
    }

    private static String flag(int letter) {
        return "-" + Character.toString(letter);
    }

    private static boolean takesValue(OptionSpec option) {
        return option != null && option.arity().min() > 0;
    }

    /**
     * A group with more flags that take a value than words after it, such as {@code -yes} typed last.
     * It keeps the group as typed, so the error handler can hint at the long option the user may have meant.
     */
    static final class MissingValueException extends MissingParameterException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String group;

        MissingValueException(CommandLine command, OptionSpec option, String flag, String group) {
            super(command, option, "Missing required parameter for option '%s' (%s) in '%s'"
                    .formatted(flag, option.paramLabel(), group));
            this.group = group;
        }

        /**
         * @return the group as typed, such as {@code -yes}
         */
        String group() {
            return group;
        }
    }
}
