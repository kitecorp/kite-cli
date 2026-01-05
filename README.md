# Kite CLI

**Write once, provision anywhere.** Multi-cloud Infrastructure as Code tool.

Kite lets you define cloud infrastructure using a simple, portable language (`.kite` files) and deploy to AWS, GCP, or Azure from a single codebase.

## Installation

### Quick Install (Recommended)

**macOS / Linux:**
```bash
curl -fsSL https://cli.kitelang.cloud/scripts/install.sh | sh
```

**Windows (PowerShell):**
```powershell
irm https://cli.kitelang.cloud/scripts/install.ps1 | iex
```

**Windows (CMD):**
```cmd
curl -fsSL https://cli.kitelang.cloud/scripts/install.bat -o install.bat && install.bat
```

The installer automatically:
- Detects your OS and architecture
- Downloads the appropriate native binary (or Java distribution as fallback)
- Installs to `~/.kite/versions/{version}/` with a `current` symlink
- Installs required Java version if needed (via SDKMAN on macOS/Linux, winget on Windows)
- Adds `~/.kite/current/bin` to your PATH

**Installation structure:**
```
~/.kite/
├── versions/
│   ├── 1.0.0/
│   └── 1.1.0/
└── current -> versions/1.1.0   # symlink to active version
```

**Options via environment variables:**
```bash
# Install specific version
KITE_VERSION=1.0.0 curl -fsSL https://cli.kitelang.cloud/scripts/install.sh | sh

# Skip PATH modification
KITE_NO_MODIFY_PATH=1 curl -fsSL https://cli.kitelang.cloud/scripts/install.sh | sh
```

### Package Managers

**Homebrew (macOS/Linux):**
```bash
brew install kitecorp/tap/kite
```

**Chocolatey (Windows):**
```powershell
choco install kite-cli
```

**Snap (Linux):**
```bash
sudo snap install kite
```

### Direct Download (All Platforms)

Download pre-built binaries from [GitHub Releases](https://github.com/kitecorp/kite-cli/releases):

```bash
# Linux x64
curl -LO https://github.com/kitecorp/kite-cli/releases/latest/download/kite-VERSION-linux-amd64.zip
unzip kite-VERSION-linux-amd64.zip
sudo mv bin/kite /usr/local/bin/

# Linux ARM64
curl -LO https://github.com/kitecorp/kite-cli/releases/latest/download/kite-VERSION-linux-arm64.zip

# macOS (if not using Homebrew)
curl -LO https://github.com/kitecorp/kite-cli/releases/latest/download/kite-VERSION-osx-arm64.zip
```

### Java Distribution (Cross-Platform)

If you have Java 25+ installed, the universal Java distribution works on any platform:

```bash
# Download the Java distribution
curl -LO https://github.com/kitecorp/kite-cli/releases/latest/download/kite-VERSION-java.zip
unzip kite-VERSION-java.zip

# Run via the launcher script
./kite-VERSION/bin/kite --version

# Or add to PATH
export PATH="$PWD/kite-VERSION/bin:$PATH"
```

This is useful for platforms without native builds or when you already have Java installed.

### From Source

```bash
# Build
./gradlew build

# Run directly
./gradlew run --args="<command>"

# Or use the distribution
./gradlew installDist
./build/install/kite/bin/kite <command>
```

## Quick Start

```bash
# Create a new project (interactive)
kite new my-project
cd my-project

# Install providers
kite providers install

# Validate your .kite files
kite validate

# Preview changes for dev environment
kite plan --env dev

# Apply changes
kite apply --env dev

# View outputs (endpoints, ARNs, etc.)
kite output --env dev

# Tear down when done
kite destroy --env dev
```

## Commands

### `kite new [project-name]`

Create a new Kite project. **Interactive mode is the default** - you'll be prompted for project name and providers.

```bash
kite new                            # Interactive: prompts for name and providers
kite new my-app                     # Interactive with project name preset
kite new my-app -y                  # Non-interactive with defaults
kite new my-app -p aws,gcp          # Specify providers
kite new my-app -e dev,prod         # Only dev and prod environments
```

Aliases: `kite create`

**Options:**
| Flag | Description |
|------|-------------|
| `-d, --directory` | Target directory |
| `-p, --providers` | Cloud providers (comma-separated: aws, gcp, azure, files) |
| `-e, --env` | Environments to create (default: dev,staging,prod) |
| `-f, --force` | Overwrite existing files |
| `-y, --yes` | Skip interactive prompts, use defaults |

### `kite validate [path]`

Validate `.kite` files for syntax errors, import resolution, and portability rules.

```bash
kite validate                       # Validate all files in current directory
kite validate components/           # Validate specific directory
kite validate --strict              # Treat warnings as errors
kite validate -q                    # Quiet mode (errors only)
```

**Options:**
| Flag | Description |
|------|-------------|
| `-r, --recursive` | Recursively validate subdirectories (default: true) |
| `--strict` | Treat warnings as errors |
| `-q, --quiet` | Only output errors |
| `--format` | Output format: text, json |

**Checks performed:**
- Syntax correctness (balanced braces, valid statements)
- Import resolution (imported files exist)
- Portability rules (components don't import from `cloud/`)

### `kite plan`

Preview infrastructure changes without applying them. Compares desired state (`.kite` files) with current state.

```bash
kite plan --env dev                 # Plan all stacks in dev environment
kite plan --env prod --stack Backend    # Plan specific stack
kite plan --env dev --provider aws  # Plan only AWS resources
kite plan --env dev --json          # Output as JSON
kite plan --env dev -o plan.json    # Save plan for later apply
```

**Options:**
| Flag | Description |
|------|-------------|
| `-e, --environment` | Target environment (default: dev) |
| `-s, --stack` | Specific stack to plan (e.g., Backend, Frontend) |
| `-p, --provider` | Target provider: aws, gcp, azure, or all |
| `-f, --file` | Override: plan a specific .kite file |
| `-o, --out` | Save plan to file |
| `--target` | Plan only specific resource(s) |
| `--no-refresh` | Skip state refresh before planning |
| `--compact` | Show compact diff output |
| `--json` | Output plan as JSON |

**Plan output legend:**
```
+ create    New resource to be created
~ update    Existing resource to be modified
- destroy   Existing resource to be removed
~ replace   Resource must be destroyed and recreated
```

### `kite apply`

Apply infrastructure changes to provision resources.

```bash
kite apply --env dev                # Apply all stacks in dev
kite apply --env prod --stack Backend   # Apply specific stack
kite apply --env dev -y             # Skip confirmation prompt
kite apply --env dev --dry-run      # Preview without applying
```

**Options:**
| Flag | Description |
|------|-------------|
| `-e, --env` | Target environment (default: dev) |
| `-s, --stack` | Specific stack to apply |
| `-p, --provider` | Target provider: aws, gcp, azure, or all |
| `-f, --file` | Override: apply a specific .kite file |
| `-y, --yes` | Skip interactive approval |
| `--dry-run` | Preview changes without applying |

### `kite destroy`

Destroy provisioned infrastructure. Resources are removed in reverse dependency order.

```bash
kite destroy --env dev              # Destroy all resources in dev
kite destroy --env dev --stack Backend  # Destroy specific stack
kite destroy --env dev --dry-run    # Preview what would be destroyed
kite destroy --env prod --force     # Required for production
```

**Options:**
| Flag | Description |
|------|-------------|
| `-e, --environment` | Target environment (required) |
| `-s, --stack` | Specific stack to destroy |
| `-p, --provider` | Target provider |
| `--target` | Destroy only specific resource(s) |
| `--auto-approve` | Skip confirmation prompt |
| `--force` | Required for destroying production |
| `--dry-run` | Show what would be destroyed |
| `--parallelism` | Limit concurrent operations (default: 10) |

### `kite output [name]`

Display output values from deployed infrastructure.

```bash
kite output --env dev               # Show all outputs
kite output api_endpoint --env dev  # Show specific output
kite output --env prod --json       # JSON format
kite output db_url --env dev --raw  # Raw value (for scripting)
kite output --env prod --sensitive  # Show sensitive values
```

**Options:**
| Flag | Description |
|------|-------------|
| `-e, --environment` | Target environment (default: dev) |
| `-s, --stack` | Filter outputs by stack |
| `--json` | Output in JSON format |
| `--raw` | Output raw value only (for scripting) |
| `--sensitive` | Show sensitive values (hidden by default) |

### `kite fmt [path]`

Format `.kite` files to canonical style.

```bash
# Format all .kite files in current directory (recursive)
kite fmt

# Format a specific file
kite fmt resources/database.kite

# Format a specific directory
kite fmt components/

# Check if files are formatted (for CI pipelines)
kite fmt --check

# Preview changes without writing
kite fmt --diff

# Format with 2-space indentation
kite fmt --indent 2

# Format single directory (non-recursive)
kite fmt modules/ -r=false

# CI pipeline example: check and fail if not formatted
kite fmt --check || echo "Run 'kite fmt' to fix formatting"
```

**Options:**
| Flag | Description |
|------|-------------|
| `-r, --recursive` | Recursively format subdirectories (default: true) |
| `--check` | Check if files are formatted (exit 1 if not) |
| `--diff` | Show diff of formatting changes |
| `-w, --write` | Write changes to files (default: true) |
| `--indent` | Number of spaces for indentation (default: 4) |

### `kite completion <shell>`

Generate shell completion scripts for tab-completion support.

```bash
# Bash
kite completion bash > ~/.local/share/bash-completion/completions/kite
# Or system-wide:
kite completion bash | sudo tee /etc/bash-completion.d/kite

# Zsh (add ~/.zfunc to your fpath in .zshrc)
kite completion zsh > ~/.zfunc/_kite

# Fish
kite completion fish > ~/.config/fish/completions/kite.fish
```

After installation, restart your shell or source your profile:
```bash
source ~/.bashrc   # or ~/.zshrc
```

Then use tab completion:
```bash
kite <TAB>           # Shows: new, validate, plan, apply, destroy, output, fmt
kite apply --<TAB>   # Shows: --env, --stack, --provider, --yes, --dry-run
kite plan --env <TAB>  # Shows: dev, staging, prod
```

### `kite config`

Manage global Kite CLI configuration stored in `~/.kite/config.yml`.

```bash
kite config                           # Show usage and examples
kite config set defaults.environment prod   # Set default environment
kite config set aws.profile production      # Set AWS profile
kite config get defaults.environment        # Get a value
kite config list                            # List all settings
kite config path                            # Show config file path
```

**Subcommands:**
| Command | Description |
|---------|-------------|
| `get <key>` | Get a configuration value |
| `set <key> <value>` | Set a configuration value |
| `list` | List all configuration values |
| `path` | Show configuration file path |

**Config Keys:**
| Key | Description |
|-----|-------------|
| `defaults.environment` | Default environment (dev, staging, prod) |
| `defaults.provider` | Default cloud provider |
| `defaults.output` | Output format (table, json, yaml) |
| `verbosity` | Log level (0=quiet, 1=normal, 2=verbose) |
| `aws.profile` | AWS profile name |
| `aws.region` | AWS region |
| `gcp.project` | GCP project ID |
| `azure.subscription` | Azure subscription ID |

### `kite doctor`

Diagnose and troubleshoot your Kite installation and project configuration.

```bash
kite doctor                           # Run all checks
kite doctor --verbose                 # Show all checks including passed ones
kite doctor --fix                     # Attempt to fix issues automatically
```

**Checks performed:**
- Java version (21+ required)
- Global configuration (~/.kite/config.yml)
- Project files (kitefile.yml, environments/)
- Installed providers
- Cloud credentials (AWS, GCP, Azure)
- State backend configuration

**Options:**
| Flag | Description |
|------|-------------|
| `--verbose` | Show all checks, including passed ones |
| `--fix` | Attempt to fix issues automatically |

### `kite providers`

Manage Kite providers (cloud integrations).

```bash
kite providers                           # Show usage and examples
kite providers install                   # Install providers from kitefile.yml
kite providers install aws               # Install latest version
kite providers install aws@1.0.0         # Install specific version
kite providers install myp --git github.com/org/provider  # Install from git
kite providers list                      # List local providers
kite providers list --global             # List global providers
```

**Subcommands:**
| Command | Description |
|---------|-------------|
| `install [PROVIDER[@VERSION]]` | Install providers from registry or git |
| `list` | List installed providers |

**Install Options:**
| Flag | Description |
|------|-------------|
| `--git=URL` | Install from git repository |
| `--ref=REF` | Git ref (branch, tag, or commit) |
| `--global` | Install to global directory (~/.kite/providers) |

Providers are configured in `kitefile.yml` (generated by `kite new`):
```yaml
providers:
  - name: aws
    git: github.com/kitecorp/kite-providers/aws
    ref: main

  - name: files
    git: github.com/kitecorp/kite-providers/files
    ref: main

  # Custom provider
  - name: custom
    git: github.com/myorg/my-provider
    ref: v1.0.0
```

### `kite upgrade`

Upgrade Kite CLI to the latest or specified version.

```bash
kite upgrade                          # Upgrade to latest version
kite upgrade 1.2.0                    # Upgrade to specific version
```

The upgrade command downloads the install script from GitHub and runs it with the specified version, then updates the `~/.kite/current` symlink.

### `kite version`

Manage installed Kite CLI versions.

```bash
kite version                          # Show current and installed versions
kite version list                     # List installed versions
kite version list --available         # List installed + available from GitHub
kite version install 1.2.0            # Install specific version
kite version install 1.2.0 --use      # Install and switch to it
kite version use 1.2.0                # Switch to installed version
kite version remove 1.2.0             # Remove an installed version
```

**Subcommands:**
| Command | Description |
|---------|-------------|
| `list` | List installed (and optionally available) versions |
| `install <version>` | Install a specific version |
| `use <version>` | Switch to an installed version |
| `remove <version>` | Remove an installed version |

## Project Structure

After running `kite new`, your project will have:

```
my-project/
├── kitefile.yml           # Project configuration
├── environments/          # Environment-specific stacks
│   ├── dev/
│   │   ├── Backend.kite   # Backend stack
│   │   └── Frontend.kite  # Frontend stack
│   ├── staging/
│   └── prod/
├── components/            # Portable, reusable components
│   ├── api-backend/
│   ├── web-server/
│   └── database/
├── modules/               # Application compositions
├── cloud/                 # Cloud-specific resources
│   ├── aws/
│   ├── gcp/
│   └── azure/
├── mixins/                # Provider-specific defaults
└── resources/             # Simple standalone resources
```

## Multi-Cloud Workflow

```bash
# Deploy same infrastructure to multiple clouds
kite apply --env prod --provider aws
kite apply --env prod --provider gcp
kite apply --env prod --provider azure

# Or deploy to all at once
kite apply --env prod --provider all
```

## Environment Variables

Configure credentials via environment variables:

```bash
# AWS
export KITE_AWS_PROFILE=default
export KITE_AWS_REGION=us-east-1

# GCP
export KITE_GCP_PROJECT=my-project
export KITE_GCP_CREDENTIALS_FILE=/path/to/credentials.json

# Azure
export KITE_AZURE_SUBSCRIPTION_ID=xxx
export KITE_AZURE_TENANT_ID=xxx

# State backend (PostgreSQL)
export KITE_DB_HOST=localhost
export KITE_DB_PASSWORD=secret
```

See `.env.example` in generated projects for full list.

## CLI Features

### Smart Error Messages

Kite provides helpful suggestions when you make typos or use incorrect commands:

```bash
$ kite deploy
Error: Unmatched argument at index 0: 'deploy'

Did you mean: kite apply?

Run 'kite --help' for usage information.
```

Common aliases are suggested:
- `deploy`, `run` → `apply`
- `init` → `new`
- `delete`, `remove` → `destroy`
- `check`, `lint` → `validate`
- `diff`, `preview` → `plan`

### Debug Mode

Set `KITE_DEBUG=1` for detailed error information:
```bash
KITE_DEBUG=1 kite apply --env dev
```

### Man Pages

Generate man pages for Unix systems:
```bash
./gradlew generateManPages
# Output: build/docs/man/*.adoc

# Convert to man format (requires asciidoctor)
asciidoctor -b manpage build/docs/man/kite.adoc
```

## Development

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run single test
./gradlew test --tests "cloud.kitelang.cli.SomeTest"

# Run CLI during development
./gradlew run --args="validate"

# Generate documentation
./gradlew generateManPages      # Man pages (AsciiDoc)
./gradlew generateHtmlDocs      # HTML documentation
```

## Releases

CLI releases are built and published from the parent [kitecorp/kite](https://github.com/kitecorp/kite) repository using JReleaser.

**Release artifacts:**
- GitHub releases: [kitecorp/kite-cli/releases](https://github.com/kitecorp/kite-cli/releases)
- Homebrew formula: [kitecorp/homebrew-tap](https://github.com/kitecorp/homebrew-tap)
- Chocolatey package: [chocolatey.org/packages/kite-cli](https://community.chocolatey.org/packages/kite-cli)
- Snap package: [snapcraft.io/kite](https://snapcraft.io/kite)

**Supported platforms:**

| Platform | Type | File | Requirements |
|----------|------|------|--------------|
| macOS ARM64 (Apple Silicon) | Native | `kite-VERSION-osx-arm64.zip` | None |
| macOS AMD64 (Intel) | Native | `kite-VERSION-osx-amd64.zip` | None |
| Linux AMD64 | Native | `kite-VERSION-linux-amd64.zip` | None |
| Linux ARM64 | Native | `kite-VERSION-linux-arm64.zip` | None |
| Windows AMD64 | Native | `kite-VERSION-windows-amd64.zip` | None |
| Universal | Java | `kite-VERSION-java.zip` | Java 25+ |

**Native images** are standalone executables compiled with GraalVM - no JVM required.

**Java distribution** contains `bin/` and `lib/` folders with launcher scripts and JARs. Runs on any platform with Java 25+.

**Release workflows:**

| Workflow | Purpose |
|----------|---------|
| **Kite-CLI Release** | Full build + GitHub release + publish to Homebrew, Chocolatey & Snap |
| **Publish to Homebrew** | Republish to Homebrew without rebuilding |
| **Publish to Chocolatey** | Republish to Chocolatey without rebuilding |
| **Publish to Snap** | Republish to Snap Store without rebuilding |

**To trigger a release:**
1. Go to [kitecorp/kite Actions](https://github.com/kitecorp/kite/actions)
2. Run the "Kite-CLI Release" workflow manually
3. Select which platforms to build and which package managers to publish to

**To republish without rebuilding:**
- Run "Publish to Homebrew", "Publish to Chocolatey", or "Publish to Snap" with the version number

## License

[Add license here]
