# Kite CLI Features

This document tracks implemented features in the Kite CLI.

## Commands

### `init` - Project Initialization
Creates a new Kite project with multi-cloud structure including environments, components, modules, cloud-specific resources, and mixins.

**Example:**
```bash
kite init my-project --providers aws,gcp --environments dev,prod
```

**File:** `commands/InitCommand.java`

---

### `validate` - File Validation
Validates `.kite` files for syntax errors, import resolution, and portability violations (components importing from cloud/).

**Example:**
```bash
kite validate --strict
kite validate components/ -q
```

**File:** `commands/ValidateCommand.java`

---

### `plan` - Change Preview
Shows what infrastructure changes would occur without applying them. Scopes to environment by default.

**Example:**
```bash
kite plan --env dev                    # All stacks in dev
kite plan --env prod --stack Backend   # Specific stack
kite plan --env dev --json             # JSON output
```

**File:** `commands/PlanCommand.java`

---

### `apply` - Provision Resources
Applies infrastructure changes to cloud providers. Scopes to environment by default.

**Example:**
```bash
kite apply --env dev
kite apply --env prod --stack Frontend -y
```

**File:** `commands/ApplyCommand.java`

---

### `destroy` - Teardown Infrastructure
Removes provisioned resources in reverse dependency order. Requires `--force` for production.

**Example:**
```bash
kite destroy --env dev
kite destroy --env prod --force --auto-approve
```

**File:** `commands/DestroyCommand.java`

---

### `output` - Display Outputs
Shows output values (URLs, ARNs, IPs) from deployed stacks. Supports JSON and raw modes.

**Example:**
```bash
kite output --env dev
kite output api_endpoint --env prod --raw
```

**File:** `commands/OutputCommand.java`

---

### `fmt` - Code Formatting
Formats `.kite` files with consistent style. Supports check mode for CI.

**Example:**
```bash
kite fmt --check
kite fmt components/ --diff
```

**File:** `commands/FmtCommand.java`

---

### `env` - Environment Switching
Quick command to show or switch the current default environment.

**Example:**
```bash
kite env                       # Show current environment
kite env dev                   # Switch to dev
kite env prod                  # Switch to prod
```

Shows available environments from both `kitefile.yml` and the `environments/` directory.

**File:** `commands/EnvCommand.java`

---

### `doctor` - Installation Diagnostics
Diagnoses Kite installation and project configuration, including credential validation.

**Example:**
```bash
kite doctor                    # Run all diagnostic checks
kite doctor -e prod            # Validate prod environment credentials
kite doctor --verbose          # Show detailed output
kite doctor --fix              # Attempt to fix issues automatically
```

**Checks performed:**
- Java version
- Global configuration (~/.kite/config.yml)
- Project files (kitefile.yml, .kite files)
- Installed providers
- Cloud credentials (validates using aws/gcloud/az CLI)
- State backend configuration

**File:** `commands/DoctorCommand.java`

---

### `upgrade` - Upgrade CLI

Upgrades Kite CLI to the latest or a specified version by downloading and running the install script from GitHub.

**Example:**
```bash
kite upgrade                          # Upgrade to latest
kite upgrade 1.2.0                    # Upgrade to specific version
```

**File:** `commands/UpgradeCommand.java`

---

### `version` - Version Management

Manages installed Kite CLI versions. Allows listing, installing, switching, and removing versions.

**Example:**
```bash
kite version                          # Show current and installed versions
kite version list --available         # List installed + available from GitHub
kite version install 1.2.0 --use      # Install and switch to version
kite version use 1.0.0                # Switch to installed version
kite version remove 1.0.0             # Remove an installed version
```

**Subcommands:**
- `list` - List installed (and optionally available) versions
- `install <version>` - Install a specific version
- `use <version>` - Switch to an installed version
- `remove <version>` - Remove an installed version

Versions are installed to `~/.kite/versions/{version}/` and the active version is symlinked at `~/.kite/current`.

**File:** `commands/VersionCommand.java`

---

## Credential Validation

Validates cloud provider credentials from `kitefile.yml` before running `kite apply`.

### Supported Providers

| Provider | CLI Used | Validation Method |
|----------|----------|-------------------|
| AWS | `aws` | `aws sts get-caller-identity` |
| GCP | `gcloud` | `gcloud auth list` + `gcloud projects describe` |
| Azure | `az` | `az account show` |

### Example Output
```
[Cloud credentials]
  Validating credentials for environment: prod
    ✓ aws: Account: 123456789012
    ✓ aws.west: Account: 123456789012
    ✓ gcp: Project: my-prod-project
  ✓ All credentials valid
```

### Files
- `validation/CredentialValidator.java` - Validator interface
- `validation/AwsCredentialValidator.java`
- `validation/GcpCredentialValidator.java`
- `validation/AzureCredentialValidator.java`
- `validation/CredentialValidatorRegistry.java`

---

## Project Generator

### Multi-Cloud Project Structure
Generates complete project scaffolding with:
- `kite.yaml` with resource type mappings
- Environment directories with stack files
- Portable components (api-backend, web-server, database)
- Application modules (backend-api, frontend-app)
- Cloud-specific resources (AWS DynamoDB, S3, etc.)
- Provider mixins (aws.kite, gcp.kite, azure.kite)

**File:** `generator/ProjectStructureGenerator.java`

---

## Installation Scripts

One-liner install scripts for quick setup across platforms. Automatically detects OS/architecture, downloads the appropriate binary, installs Java if needed, and configures PATH.

### macOS / Linux

```bash
curl -fsSL https://cli.kitelang.cloud/scripts/install.sh | sh
```

### Windows (PowerShell)

```powershell
irm https://cli.kitelang.cloud/scripts/install.ps1 | iex
```

### Environment Variables

| Variable              | Description                                   |
|-----------------------|-----------------------------------------------|
| `KITE_VERSION`        | Install specific version (default: latest)    |
| `KITE_NO_MODIFY_PATH` | Set to 1 to skip PATH modification            |

### Features

- Auto-detects OS (Linux, macOS, Windows) and architecture (amd64, arm64)
- Downloads native binary when available, falls back to Java distribution
- Installs to `~/.kite/versions/{version}/` with versioned directories
- Creates `~/.kite/current` symlink pointing to active version
- Installs required Java version via SDKMAN (macOS/Linux) or winget (Windows) if needed
- Adds `~/.kite/current/bin` to PATH automatically

### Installation Structure

```
~/.kite/
├── versions/
│   ├── 1.0.0/
│   │   ├── bin/
│   │   └── lib/
│   └── 1.1.0/
│       ├── bin/
│       └── lib/
└── current -> versions/1.1.0   # symlink to active version
```

Re-running the installer with a different `KITE_VERSION` installs that version and updates the `current` symlink.

**Files:** `scripts/install.sh`, `scripts/install.ps1`
