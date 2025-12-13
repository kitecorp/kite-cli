# Kite CLI

**Write once, provision anywhere.** Multi-cloud Infrastructure as Code tool.

Kite lets you define cloud infrastructure using a simple, portable language (`.kite` files) and deploy to AWS, GCP, or Azure from a single codebase.

## Installation

```bash
# Build from source
./gradlew build

# Run directly
./gradlew run --args="<command>"

# Or use the distribution
./gradlew installDist
./build/install/kite/bin/kite <command>
```

## Quick Start

```bash
# Initialize a new project
kite init my-project
cd my-project

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

### `kite init [project-name]`

Initialize a new Kite project with standard multi-cloud structure.

```bash
kite init                           # Initialize in current directory
kite init my-app                    # Create new directory 'my-app'
kite init -p aws,gcp                # Configure for AWS and GCP
kite init -e dev,prod               # Only dev and prod environments
kite init -i                        # Interactive mode with prompts
```

**Options:**
| Flag | Description |
|------|-------------|
| `-d, --directory` | Target directory |
| `-p, --providers` | Cloud providers (comma-separated: aws,gcp,azure) |
| `-e, --environments` | Environments to create (comma-separated: dev,staging,prod) |
| `-f, --force` | Initialize even if directory is not empty |
| `-i, --interactive` | Interactive mode - prompts for project configuration |

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
kite fmt                            # Format all files
kite fmt components/                # Format specific directory
kite fmt --check                    # Check formatting (CI mode)
kite fmt --diff                     # Show what would change
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
kite <TAB>           # Shows: init, validate, plan, apply, destroy, output, fmt
kite apply --<TAB>   # Shows: --env, --stack, --provider, --yes, --dry-run
kite plan --env <TAB>  # Shows: dev, staging, prod
```

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

Providers can be configured in `kitefile.yml`:
```yaml
providers:
  # Registry provider
  - name: files
    version: "0.1.0"

  # Git provider
  - name: custom
    git: github.com/myorg/kite-provider-custom
    ref: v1.0.0
```

## Project Structure

After running `kite init`, your project will have:

```
my-project/
├── kite.yaml              # Project configuration
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
- `create`, `new` → `init`
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

## License

[Add license here]
