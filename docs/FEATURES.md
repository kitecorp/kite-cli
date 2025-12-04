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
