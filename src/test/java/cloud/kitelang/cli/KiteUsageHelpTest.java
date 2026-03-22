package cloud.kitelang.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the bun-style usage help renderer.
 */
class KiteUsageHelpTest {

    @Test
    void renderContainsHeaderWithVersion() {
        var output = renderHelp();

        assertTrue(output.contains("Kite"), "Should contain product name");
        assertTrue(output.contains("Write once, provision anywhere"), "Should contain tagline");
        // Version string from VersionProvider (may be "unknown" in test without resources)
        assertTrue(output.contains("("), "Should contain version in parentheses");
    }

    @Test
    void renderContainsUsageLine() {
        var output = renderHelp();

        assertTrue(output.contains("Usage:"), "Should have Usage section");
        assertTrue(output.contains("kite <command> [...flags] [...args]"), "Should show usage pattern");
    }

    @Test
    void renderContainsAllInfrastructureCommands() {
        var output = renderHelp();

        assertAll("Infrastructure commands",
                () -> assertTrue(output.contains("new"), "Should list 'new' command"),
                () -> assertTrue(output.contains("validate"), "Should list 'validate' command"),
                () -> assertTrue(output.contains("plan"), "Should list 'plan' command"),
                () -> assertTrue(output.contains("apply"), "Should list 'apply' command"),
                () -> assertTrue(output.contains("destroy"), "Should list 'destroy' command"),
                () -> assertTrue(output.contains("output"), "Should list 'output' command")
        );
    }

    @Test
    void renderContainsAllConfigurationCommands() {
        var output = renderHelp();

        assertAll("Configuration commands",
                () -> assertTrue(output.contains("fmt"), "Should list 'fmt' command"),
                () -> assertTrue(output.contains("env"), "Should list 'env' command"),
                () -> assertTrue(output.contains("config"), "Should list 'config' command"),
                () -> assertTrue(output.contains("providers"), "Should list 'providers' command")
        );
    }

    @Test
    void renderContainsAllUtilityCommands() {
        var output = renderHelp();

        assertAll("Utility commands",
                () -> assertTrue(output.contains("doctor"), "Should list 'doctor' command"),
                () -> assertTrue(output.contains("version"), "Should list 'version' command"),
                () -> assertTrue(output.contains("upgrade"), "Should list 'upgrade' command"),
                () -> assertTrue(output.contains("completion"), "Should list 'completion' command")
        );
    }

    @Test
    void renderContainsInlineExamples() {
        var output = renderHelp();

        assertAll("Inline examples next to commands",
                () -> assertTrue(output.contains("my-project"), "Should show example arg for 'new'"),
                () -> assertTrue(output.contains("-e dev"), "Should show example arg for 'plan'"),
                () -> assertTrue(output.contains("-e prod"), "Should show example arg for 'apply'"),
                () -> assertTrue(output.contains("get <key>"), "Should show example arg for 'config'")
        );
    }

    @Test
    void renderContainsFlags() {
        var output = renderHelp();

        assertAll("Global flags",
                () -> assertTrue(output.contains("Flags:"), "Should have Flags section"),
                () -> assertTrue(output.contains("-h"), "Should list -h flag"),
                () -> assertTrue(output.contains("--help"), "Should list --help flag"),
                () -> assertTrue(output.contains("-v"), "Should list -v flag"),
                () -> assertTrue(output.contains("--version"), "Should list --version flag")
        );
    }

    @Test
    void renderContainsHelpHint() {
        var output = renderHelp();

        assertTrue(output.contains("<command>"), "Should have per-command help hint");
        assertTrue(output.contains("Print help text for command"), "Should describe the hint");
    }

    @Test
    void renderContainsFooterLink() {
        var output = renderHelp();

        assertTrue(output.contains("Learn more about Kite"), "Should have footer");
        assertTrue(output.contains("https://kite.cloud/docs"), "Should have docs link");
    }

    @Test
    void renderContainsGroupHeaders() {
        var output = renderHelp();

        assertAll("Group headers",
                () -> assertTrue(output.contains("Infrastructure:"), "Should have Infrastructure header"),
                () -> assertTrue(output.contains("Configuration:"), "Should have Configuration header"),
                () -> assertTrue(output.contains("Utility:"), "Should have Utility header")
        );
    }

    @Test
    void buildCommandGroupsReturnsThreeGroups() {
        var groups = KiteUsageHelp.buildCommandGroups();

        assertEquals(3, groups.size(), "Should have 3 command groups");
        assertEquals("Infrastructure", groups.get(0).label());
        assertEquals("Configuration", groups.get(1).label());
        assertEquals("Utility", groups.get(2).label());
    }

    @Test
    void buildCommandGroupsInfrastructureHasSixEntries() {
        var groups = KiteUsageHelp.buildCommandGroups();
        var infra = groups.get(0);

        assertEquals(6, infra.entries().size(), "Infrastructure group should have 6 commands");

        var names = infra.entries().stream()
                .map(KiteUsageHelp.CommandEntry::name)
                .toList();
        assertEquals(List.of("new", "validate", "plan", "apply", "destroy", "output"), names);
    }

    @Test
    void buildCommandGroupsConfigurationHasFourEntries() {
        var groups = KiteUsageHelp.buildCommandGroups();
        var config = groups.get(1);

        assertEquals(4, config.entries().size(), "Configuration group should have 4 commands");

        var names = config.entries().stream()
                .map(KiteUsageHelp.CommandEntry::name)
                .toList();
        assertEquals(List.of("fmt", "env", "config", "providers"), names);
    }

    @Test
    void buildCommandGroupsUtilityHasFourEntries() {
        var groups = KiteUsageHelp.buildCommandGroups();
        var utility = groups.get(2);

        assertEquals(4, utility.entries().size(), "Utility group should have 4 commands");

        var names = utility.entries().stream()
                .map(KiteUsageHelp.CommandEntry::name)
                .toList();
        assertEquals(List.of("doctor", "version", "upgrade", "completion"), names);
    }

    @Test
    void commandEntryWithEmptyExampleRendersCleanly() {
        var output = renderHelp();

        // "doctor" has empty example - should not leave awkward spacing artifacts
        // Just verify it renders without throwing
        assertTrue(output.contains("doctor"), "Should render command with empty example");
        assertTrue(output.contains("Check your Kite installation"), "Should show doctor description");
    }

    /**
     * Renders help to a string for assertion.
     */
    private String renderHelp() {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw);
        KiteUsageHelp.render(pw);
        return sw.toString();
    }
}
