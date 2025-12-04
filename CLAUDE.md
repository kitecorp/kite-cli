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
│   └── ApplyCommand.java     # Apply infrastructure changes
└── generator/
    └── ProjectStructureGenerator.java  # Generates project scaffolding
```

### Key Technologies

- **Java 25** with preview features enabled
- **Picocli** for CLI command parsing and help generation
- **Lombok** for boilerplate reduction (@Log4j2, etc.)
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
