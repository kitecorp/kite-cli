# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kite CLI is a multi-cloud Infrastructure as Code (IaC) tool built with Java. It provides a "write once, provision anywhere" approach using a custom `.kite` language for defining cloud resources that can be deployed to AWS, GCP, or Azure.

## Build Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "cloud.kitelang.cli.SomeTest"

# Clean build
./gradlew clean build

# Run the CLI
./gradlew run --args="<command>"
```

## Architecture

### Package Structure

```
cloud.kitelang.cli/
├── KiteCLI.java              # Main entry point (picocli @Command)
├── commands/                  # CLI subcommands
│   ├── InitCommand.java      # Initialize new Kite project
│   ├── ValidateCommand.java  # Validate .kite files
│   ├── PlanCommand.java      # Preview infrastructure changes
│   ├── ApplyCommand.java     # Apply infrastructure changes
│   ├── DestroyCommand.java   # Tear down infrastructure
│   ├── OutputCommand.java    # Display stack outputs
│   └── FmtCommand.java       # Format .kite files
└── generator/
    └── ProjectStructureGenerator.java  # Generates project scaffolding
```

### Key Technologies

- **Java 25** with preview features enabled
- **Picocli** for CLI command parsing and help generation
- **Lombok** for boilerplate reduction (@Slf4j, etc.)
- **Log4j2** for logging
- **JUnit 5** (JUnitPlatform) for testing

### CLI Command Pattern

Commands implement `Callable<Integer>` and use picocli annotations:
- `@Command` on class defines the subcommand
- `@Parameters` for positional arguments
- `@Option` for named flags
- Return `0` for success, `1` for failure

### Kite Project Structure

When `kite init` runs, it generates:
- `kite.yaml` - Project configuration with provider mappings
- `environments/{dev,staging,prod}/` - Environment-specific stacks
- `components/` - Portable, cloud-agnostic components
- `modules/` - Application-level compositions
- `cloud/{aws,gcp,azure}/` - Cloud-specific resources
- `mixins/` - Provider-specific defaults (strategy pattern)
- `resources/` - Simple standalone resources

### Multi-Cloud Portability Model

1. **Generic Resource Types**: Components use abstract types (`Bucket`, `Function`, `Database`)
2. **kite.yaml Mappings**: Translates generic types to cloud-specific resources at deploy time
3. **Import Rules**: Components must NOT import from `cloud/` to stay portable
4. **Mixins**: Inject cloud-specific properties without modifying portable code

## Code Style

- Use `var` for local variable declarations where possible
- Document code with JavaDoc for public APIs
- Follow TDD - write tests first
- Prefer reusing existing utilities over creating new ones

## Terminal I/O - Use JLine

**Always use JLine for terminal input/output instead of `System.console()` or `System.out`.**

JLine provides cross-platform terminal handling that works correctly on Windows, macOS, and Linux.

### For Console Output
Use `Console` class (wraps JLine terminal):
```java
import cloud.kitelang.cli.console.Console;

Console.println("message");
Console.print("no newline");
Console.printf("formatted %s", value);
Console.error("error message");    // Red
Console.success("success");        // Green checkmark
Console.warning("warning");        // Yellow
Console.header("Section Title");   // Bold header
```

### For User Input
Use `InteractivePrompt` class (wraps JLine ConsoleUI):
```java
import cloud.kitelang.cli.interactive.InteractivePrompt;

try (var prompt = InteractivePrompt.create()) {
    if (prompt != null) {
        // Yes/No confirmation
        boolean confirmed = prompt.confirm("Proceed?", false);

        // Single selection from list
        String choice = prompt.selectOne("Choose:", List.of(
            new Option("id1", "Label 1"),
            new Option("id2", "Label 2")
        ));

        // Multi-select checkboxes
        List<String> selected = prompt.selectMany("Select items:", options);

        // Text input
        String value = prompt.input("Enter value:", "default");

        // Password (masked)
        String password = prompt.password("Enter password:");
    }
}
```

### Why Not System.console()?
- `System.console().readLine()` doesn't handle line endings consistently across OS
- Causes `^M` characters on some terminals
- No support for arrow keys, history, or advanced input
- Returns null when running without a TTY (IDE, pipes)
