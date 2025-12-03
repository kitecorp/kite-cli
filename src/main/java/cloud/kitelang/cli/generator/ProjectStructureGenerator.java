package cloud.kitelang.cli.generator;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the standard Kite project structure for multi-cloud IaC.
 */
@Log4j2
public class ProjectStructureGenerator {

    /**
     * Generate a new Kite project structure.
     *
     * @param projectDir   The target directory for the project
     * @param projectName  The name of the project
     * @param providers    Cloud providers to configure (aws, gcp, azure)
     * @param environments Environments to create (dev, staging, prod)
     * @param force        Force creation even if directory is not empty
     */
    public void generate(Path projectDir, String projectName, String[] providers,
                         String[] environments, boolean force) throws IOException {

        // Check if directory exists and is not empty
        if (Files.exists(projectDir) && !isDirectoryEmpty(projectDir) && !force) {
            throw new IOException(
                "Directory is not empty. Use --force to initialize anyway, or choose a different directory."
            );
        }

        log.info("Creating project structure in: {}", projectDir);

        // Create base directory structure
        createDirectories(projectDir, providers, environments);

        // Generate configuration files
        generateKiteYaml(projectDir, projectName);
        generateGitignore(projectDir);
        generateReadme(projectDir, projectName);
        generateEnvExample(projectDir);

        // Create environment configurations with stack files
        for (var env : environments) {
            generateEnvironmentConfig(projectDir, env, providers);
        }

        // Create sample resource files
        generateSampleResources(projectDir);

        // Create portable components
        generateSampleComponents(projectDir);

        // Create application modules
        generateModules(projectDir);

        // Create cloud-specific resources
        generateCloudSpecificResources(projectDir, providers);

        log.info("Project structure created successfully");
    }

    private void createDirectories(Path projectDir, String[] providers, String[] environments) throws IOException {
        var directories = new java.util.ArrayList<Path>();

        // Base directories
        directories.add(projectDir);

        // Environment subdirectories with stack files
        directories.add(projectDir.resolve("environments"));
        for (var env : environments) {
            directories.add(projectDir.resolve("environments/" + env));
        }

        // Resources (simple standalone resources, no subdirectories)
        directories.add(projectDir.resolve("resources"));

        // Components (portable, reusable with inputs/outputs)
        directories.add(projectDir.resolve("components"));
        directories.add(projectDir.resolve("components/api-backend"));
        directories.add(projectDir.resolve("components/web-server"));
        directories.add(projectDir.resolve("components/database"));

        // Modules (application-level compositions)
        directories.add(projectDir.resolve("modules"));
        directories.add(projectDir.resolve("modules/backend-api"));
        directories.add(projectDir.resolve("modules/frontend-app"));

        // Cloud-specific resources and components
        directories.add(projectDir.resolve("cloud"));
        for (var provider : providers) {
            directories.add(projectDir.resolve("cloud/" + provider));
            // Add serverless-api component for AWS
            if ("aws".equals(provider)) {
                directories.add(projectDir.resolve("cloud/" + provider + "/serverless-api"));
            }
        }

        for (var dir : directories) {
            Files.createDirectories(dir);
            log.debug("Created directory: {}", dir);
        }
    }

    private void generateKiteYaml(Path projectDir, String projectName) throws IOException {
        var content = """
            # Kite Project Configuration
            project:
              name: %s
              version: 1.0.0
              kite_version: ">=0.1.0"

            # State backend configuration (PostgreSQL)
            state:
              backend: postgresql
              config:
                host: ${KITE_DB_HOST:-localhost}
                port: ${KITE_DB_PORT:-5432}
                database: ${KITE_DB_NAME:-kite_state}
                username: ${KITE_DB_USER:-kite}
                password: ${KITE_DB_PASSWORD}
                schema: ${KITE_DB_SCHEMA:-public}

            # Cloud provider credentials
            # Use environment variables (KITE_*) for sensitive values
            providers:
              aws:
                enabled: true
                profile: ${KITE_AWS_PROFILE:-default}
                region: ${KITE_AWS_REGION:-us-east-1}
                account_id: ${KITE_AWS_ACCOUNT_ID}
                # Optional: Use explicit credentials
                # access_key_id: ${KITE_AWS_ACCESS_KEY_ID}
                # secret_access_key: ${KITE_AWS_SECRET_ACCESS_KEY}

              gcp:
                enabled: false
                project: ${KITE_GCP_PROJECT}
                region: ${KITE_GCP_REGION:-us-central1}
                # Path to service account JSON or use Application Default Credentials
                credentials_file: ${KITE_GCP_CREDENTIALS_FILE}

              azure:
                enabled: false
                subscription_id: ${KITE_AZURE_SUBSCRIPTION_ID}
                tenant_id: ${KITE_AZURE_TENANT_ID}
                client_id: ${KITE_AZURE_CLIENT_ID}
                client_secret: ${KITE_AZURE_CLIENT_SECRET}
                resource_group: ${KITE_AZURE_RESOURCE_GROUP}
                location: ${KITE_AZURE_LOCATION:-eastus}

            # Default settings for all environments
            defaults:
              provider: aws
              region: us-east-1

            # Cloud-specific resource mappings
            # Maps generic resource types to cloud-specific implementations
            mappings:
              # Storage
              Bucket:
                aws: AWS::S3::Bucket
                gcp: google.storage.Bucket
                azure: azure.storage.StorageAccount

              # Compute
              Function:
                aws: AWS::Lambda::Function
                gcp: google.cloudfunctions.Function
                azure: azure.functions.Function

              Compute:
                aws: AWS::EC2::Instance
                gcp: google.compute.Instance
                azure: azure.compute.VirtualMachine

              # Database
              Database:
                aws: AWS::RDS::DBInstance
                gcp: google.sql.DatabaseInstance
                azure: azure.sql.Server

              DatabaseInstance:
                aws: AWS::RDS::DBInstance
                gcp: google.sql.DatabaseInstance
                azure: azure.sql.Server

              # Networking
              VPC:
                aws: AWS::EC2::VPC
                gcp: google.compute.Network
                azure: azure.network.VirtualNetwork

              SecurityGroup:
                aws: AWS::EC2::SecurityGroup
                gcp: google.compute.Firewall
                azure: azure.network.NetworkSecurityGroup

            # Environment variable prefix
            # All Kite environment variables should start with KITE_
            env_prefix: KITE_
            """.formatted(projectName);

        Files.writeString(projectDir.resolve("kite.yaml"), content);
        log.debug("Generated kite.yaml");
    }

    private void generateGitignore(Path projectDir) throws IOException {
        var content = """
            # Generated outputs
            outputs/

            # Environment files with secrets
            .env
            .env.*
            .envrc

            # Temporary credentials (if any)
            **/credentials.yaml
            **/secrets.yaml
            **/*-credentials.json

            # IDE files
            .idea/
            .vscode/
            *.iml

            # OS files
            .DS_Store
            Thumbs.db

            # Build artifacts
            *.class
            *.jar
            build/
            target/
            """;

        Files.writeString(projectDir.resolve(".gitignore"), content);
        log.debug("Generated .gitignore");
    }

    private void generateReadme(Path projectDir, String projectName) throws IOException {
        var content = """
            # %s

            Multi-cloud Infrastructure as Code project built with Kite.

            ## Project Structure

            ```
            .
            ├── kite.yaml              # Project configuration with resource mappings
            ├── .env.example           # Example environment variables
            ├── .gitignore             # Git ignore rules
            │
            ├── environments/          # Environment-specific stacks
            │   ├── dev/
            │   │   ├── config.yaml    # Dev environment configuration
            │   │   ├── Backend.kite   # Backend stack for dev
            │   │   └── Frontend.kite  # Frontend stack for dev
            │   ├── staging/
            │   │   ├── config.yaml
            │   │   ├── Backend.kite
            │   │   └── Frontend.kite
            │   └── prod/
            │       ├── config.yaml
            │       ├── Backend.kite
            │       └── Frontend.kite
            │
            ├── components/            # Portable, cloud-agnostic components
            │   ├── README.md          # Component organization guide
            │   ├── api-backend/       # API backend component
            │   │   ├── component.kite
            │   │   └── README.md
            │   ├── web-server/        # Web server component
            │   │   ├── component.kite
            │   │   └── README.md
            │   └── database/          # Database component
            │       ├── component.kite
            │       └── README.md
            │
            ├── modules/               # Application-level compositions
            │   ├── README.md          # Module organization guide
            │   ├── backend-api/       # Backend API module
            │   │   ├── module.kite
            │   │   └── README.md
            │   └── frontend-app/      # Frontend app module
            │       ├── module.kite
            │       └── README.md
            │
            ├── cloud/                 # Cloud-specific resources (importable)
            │   ├── aws/
            │   │   ├── dynamodb.kite      # DynamoDB tables
            │   │   ├── route53.kite       # Route53 zones
            │   │   ├── storage.kite       # S3 buckets
            │   │   └── serverless-api/    # AWS serverless component
            │   │       ├── component.kite
            │   │       └── README.md
            │   ├── gcp/
            │   └── azure/
            │
            └── resources/             # Simple standalone resources
                └── main.kite
            ```

            ## Understanding Kite Architecture

            ### Resource Schemas vs Imports

            **IMPORTANT**: There's a key distinction between resource schemas and imports:

            #### Resource Schemas (from Provider Plugins)
            Resource types like `DynamoDBTable`, `S3Bucket`, `Lambda` are **NOT defined in .kite files**.
            They come from provider plugins (Java classes with `@TypeName` and `@Schema` annotations).

            ```kite
            // You don't import DynamoDBTable schema - it's provided by AWS plugin
            resource DynamoDBTable users {
                table_name = "users"
                hash_key = "user_id"
            }
            ```

            #### Imports (for Code Reuse)
            Use `import * from "path"` to import **resource instances**, **variables**, and **functions**:

            ```kite
            // Import resource instances from a .kite file
            import * from "../../cloud/aws/dynamodb.kite"

            // Now you can reference: userTable, ordersTable (defined in dynamodb.kite)
            resource Lambda handler {
                environment_vars = {
                    TABLE_NAME = userTable.name  // From import
                }
            }
            ```

            ### Component Portability: Import Direction Rules

            To maintain "write once, deploy anywhere", we enforce import direction rules:

            #### ✅ ALLOWED
            - **Components → Components**: `import * from "../other-component/component.kite"`
            - **Cloud → Components**: `import * from "../../components/api-backend/component.kite"`
            - **Cloud → Same Cloud**: `import * from "../dynamodb.kite"`
            - **Environments → Anything**: Import from components/ or cloud/

            #### ❌ FORBIDDEN
            - **Components → Cloud**: `import * from "../../cloud/aws/..."` ❌
              - This breaks portability! Components must stay cloud-agnostic

            ### Multi-Cloud Integration Layers

            Kite provides four ways to integrate portable and cloud-specific code:

            #### 1. kite.yaml Mappings (Automatic Translation)
            Generic resource types are automatically mapped to cloud-specific implementations:

            ```yaml
            # In kite.yaml
            mappings:
              Bucket:
                aws: AWS::S3::Bucket
                gcp: google.storage.Bucket
                azure: azure.storage.StorageAccount
            ```

            ```kite
            // In portable component - uses generic Bucket
            resource Bucket data {
                name = "my-data"
            }
            // Deploys to S3 on AWS, Cloud Storage on GCP, Blob Storage on Azure
            ```

            #### 2. Environment Composition
            Environment stacks import and compose portable + cloud-specific resources:

            ```kite
            // In environments/prod/Backend.kite
            import * from "../../components/api-backend/component.kite"  // Portable
            import * from "../../cloud/aws/dynamodb.kite"                // Cloud-specific

            ApiBackend api {
                bucketName = "prod-data"  // → S3 on AWS deployment
            }

            resource Lambda cron {
                dynamodb_table = userTable.name  // From cloud-specific import
            }
            ```

            #### 3. Cloud-Specific Component Wrappers
            Create cloud-specific components that wrap/extend portable ones:

            ```kite
            // In cloud/aws/serverless-api/component.kite
            import * from "../../components/api-backend/component.kite"
            import * from "../dynamodb.kite"

            component AwsServerlessApi api {
                // Compose portable component with AWS-specific resources
            }
            ```

            #### 4. Mixins (Future Feature)
            Inject cloud-specific properties into generic resources (planned).

            ### Modules: Application-Level Compositions

            Modules combine multiple components into complete applications. They sit between components and environments in the hierarchy.

            #### When to Use Modules

            Use modules when you want to:
            - Package a complete application (backend API, frontend app, data pipeline)
            - Combine multiple components that work together
            - Create reusable application patterns
            - Define application-level logic and orchestration

            #### Module Example

            ```kite
            // modules/backend-api/module.kite
            import * from "../../components/api-backend/component.kite"
            import * from "../../components/database/component.kite"

            component BackendApiModule api {
                input string environment

                // Compose components
                ApiBackend backend {
                    bucketName = "${environment}-api-data"
                    dbName = "${environment}-db"
                }

                Database db {
                    engine = "postgres"
                    instanceClass = "db.t3.small"
                    vpcId = "vpc-12345"
                    storageSize = 100
                    backupRetentionDays = 7
                }

                // Module outputs
                output string apiEndpoint = backend.bucketArn
                output string dbConnection = db.connectionString
            }
            ```

            #### Using Modules in Environments

            ```kite
            // environments/prod/Backend.kite
            import * from "../../modules/backend-api/module.kite"

            BackendApiModule prodApi {
                environment = "prod"
            }
            ```

            #### Module Portability

            - Modules that only import from `components/` are **portable** (work on any cloud)
            - Modules that import from `cloud/` are **cloud-specific** (locked to one provider)
            - Choose based on your needs: portability vs provider-specific features

            ## Getting Started

            ### 1. Configure Environment Variables

            Copy `.env.example` to `.env` and fill in your values:

            ```bash
            cp .env.example .env
            # Edit .env with your credentials
            ```

            Or export environment variables:

            ```bash
            # Database (for state storage)
            export KITE_DB_HOST=localhost
            export KITE_DB_PASSWORD=your-password

            # AWS
            export KITE_AWS_PROFILE=default
            export KITE_AWS_REGION=us-east-1
            export KITE_AWS_ACCOUNT_ID=123456789012

            # GCP (if using)
            export KITE_GCP_PROJECT=my-project
            export KITE_GCP_CREDENTIALS_FILE=/path/to/service-account.json

            # Azure (if using)
            export KITE_AZURE_SUBSCRIPTION_ID=your-sub-id
            export KITE_AZURE_TENANT_ID=your-tenant-id
            ```

            ### 2. Define Your Infrastructure

            #### Option A: Use Portable Components (Recommended)
            Define in `components/` for multi-cloud portability:

            ```kite
            // components/my-app/component.kite
            component MyApp app {
                input string bucketName

                resource Bucket data {
                    name = bucketName
                }

                resource Function handler {
                    runtime = "nodejs20"
                }

                output string bucketArn = data.arn
            }
            ```

            #### Option B: Use Cloud-Specific Resources
            Define in `cloud/aws/` for AWS-specific features:

            ```kite
            // cloud/aws/my-service.kite
            resource DynamoDBTable data {
                table_name = "my-data"
                billing_mode = "PAY_PER_REQUEST"
            }
            ```

            #### Option C: Simple Resources
            Define in `resources/` for simple standalone resources.

            ### 3. Deploy to Environments

            Create stack files in `environments/{env}/`:

            ```kite
            // environments/dev/Backend.kite
            import * from "../../components/my-app/component.kite"

            MyApp devApp {
                bucketName = "dev-my-app-data"
            }
            ```

            ### 4. Run Kite Commands

            ```bash
            kite validate                    # Validate all .kite files
            kite plan --env dev              # Preview changes for dev
            kite apply --env dev             # Deploy to dev environment
            kite destroy --env dev           # Tear down dev environment
            ```

            ## Common Commands

            ```bash
            # Validation
            kite validate                    # Validate all .kite files

            # Planning
            kite plan --env dev              # Preview dev changes
            kite plan --env prod             # Preview prod changes

            # Deployment
            kite apply --env dev             # Deploy to dev
            kite apply --env prod --provider aws    # Deploy prod to AWS
            kite apply --env prod --provider gcp    # Deploy prod to GCP

            # Destruction
            kite destroy --env dev           # Tear down dev environment
            ```

            ## Multi-Cloud Deployment

            Deploy the same infrastructure to multiple clouds:

            ```bash
            # Deploy to all configured providers
            kite apply --env prod --provider all

            # Or deploy to specific providers
            kite apply --env prod --provider aws
            kite apply --env prod --provider gcp
            kite apply --env prod --provider azure
            ```

            ## Best Practices

            1. **Keep Components Portable**: Never import from `cloud/` in `components/`
            2. **Use Generic Types**: Prefer `Bucket`, `Function`, `Database` over cloud-specific types in components
            3. **Environment-Specific Stacks**: Use `environments/{env}/*.kite` for deployment compositions
            4. **Reuse Cloud Resources**: Define common cloud-specific resources in `cloud/` and import them
            5. **Document Components**: Add README.md to each component explaining inputs/outputs
            6. **Version Control**: Never commit `.env` files - use `.env.example` as template

            ## Project Organization Tips

            ### Directory Hierarchy

            ```
            Resources     → Simple standalone resources (lowest level)
            Components    → Reusable infrastructure patterns (mid level)
            Modules       → Application compositions (high level)
            Environments  → Deployment targets (orchestration)
            ```

            - **resources/**: Quick, simple resource declarations
            - **components/**: Reusable building blocks that work anywhere
            - **modules/**: Application-level compositions (combine multiple components)
            - **cloud/**: Provider-specific resources and optimizations
            - **environments/**: Actual deployment configurations per environment

            ## Next Steps

            1. Review the generated components in `components/`
            2. Check out cloud-specific examples in `cloud/aws/`
            3. Customize environment stacks in `environments/`
            4. Read component READMEs for usage examples
            5. Run `kite validate` to ensure everything is configured correctly
            """.formatted(projectName);

        Files.writeString(projectDir.resolve("README.md"), content);
        log.debug("Generated README.md");
    }

    private void generateEnvironmentConfig(Path projectDir, String env, String[] providers) throws IOException {
        var envDir = projectDir.resolve("environments").resolve(env);
        Files.createDirectories(envDir);

        // Generate config.yaml with provider settings
        var configContent = """
            # Environment: %s
            environment: %s

            # Provider configuration for this environment
            providers:
              aws:
                account_id: "%s"
                region: %s
                profile: %s

              gcp:
                project: "my-gcp-project-%s"
                region: us-central1

              azure:
                subscription_id: "your-subscription-id"
                resource_group: "rg-%s-%s"
                location: eastus

            # Environment-specific settings
            settings:
              auto_scaling: %s
              backup_enabled: %s
              monitoring_level: %s
            """.formatted(
            env,
            env,
            env.equals("prod") ? "987654321098" : "123456789012",
            env.equals("prod") ? "us-east-1" : "us-west-2",
            env,
            env,
            projectDir.getFileName().toString(),
            env,
            env.equals("prod") ? "true" : "false",
            env.equals("prod") ? "true" : "false",
            env.equals("prod") ? "detailed" : "basic"
        );

        Files.writeString(envDir.resolve("config.yaml"), configContent);

        // Generate Backend.kite stack file
        var backendStack = """
            // Backend Stack for %s environment
            // Import portable components
            import * from "../../components/api-backend/component.kite"
            import * from "../../components/database/component.kite"

            // Import cloud-specific resources
            import * from "../../cloud/aws/dynamodb.kite"

            // Deploy portable API backend component
            // Generic types are automatically mapped to cloud-specific resources via kite.yaml
            ApiBackend backend {
                bucketName = "%s-api-data"
                dbName = "%s-database"
            }

            // Deploy database component
            Database mainDb {
                engine = "postgres"
                instanceClass = "%s"
                vpcId = "vpc-12345"
                storageSize = %d
                backupRetentionDays = %d
            }

            // Add cloud-specific resources that reference component outputs
            resource Lambda cronJob {
                name = "%s-cron-job"
                runtime = "nodejs20"
                environment_vars = {
                    API_BUCKET = backend.bucketArn
                    DB_ENDPOINT = mainDb.connectionString
                    DYNAMODB_TABLE = userTable.name  // From cloud/aws/dynamodb.kite import
                }
            }
            """.formatted(
            env,
            env,
            env,
            env.equals("prod") ? "db.m5.large" : "db.t3.small",
            env.equals("prod") ? 200 : 50,
            env.equals("prod") ? 14 : 7,
            env
        );

        Files.writeString(envDir.resolve("Backend.kite"), backendStack);

        // Generate Frontend.kite stack file
        var frontendStack = """
            // Frontend Stack for %s environment
            // Import portable components
            import * from "../../components/web-server/component.kite"

            // Import cloud-specific resources
            import * from "../../cloud/aws/storage.kite"

            // Deploy web server component
            // Generic Compute type is automatically mapped to EC2/Compute Engine/VM via kite.yaml
            WebServer frontend {
                instanceType = "%s"
                vpcId = "vpc-12345"
                subnetIds = ["subnet-1", "subnet-2"]
                enableMonitoring = %s
            }

            // Use imported S3 bucket from cloud/aws/storage.kite
            resource CloudFront cdn {
                name = "%s-cdn"
                origin_bucket = staticAssets.id  // From cloud/aws/storage.kite import
                enabled = true
            }

            // Output frontend URL
            output string frontendUrl = frontend.publicIp
            output string cdnUrl = cdn.domain_name
            """.formatted(
            env,
            env.equals("prod") ? "m5.large" : "t3.medium",
            env.equals("prod") ? "true" : "false",
            env
        );

        Files.writeString(envDir.resolve("Frontend.kite"), frontendStack);

        log.debug("Generated configuration and stack files for environment: {}", env);
    }

    private void generateSampleResources(Path projectDir) throws IOException {
        var mainKite = """
            // Simple standalone resources
            // Use this directory for quick resource declarations without component wrappers

            // IMPORTANT: Resource schemas are provided by cloud provider plugins
            // You don't define schemas - you instantiate resources using plugin-provided types

            // Example 1: Simple storage bucket
            resource Bucket myAppData {
                name = "my-app-data"
                versioning = true
                encryption = true
            }

            // Example 2: Serverless function
            resource Function apiHandler {
                name = "api-handler"
                runtime = "nodejs20"
                memory = 512
                handler = "index.handler"
            }

            // Example 3: Composition with imports
            // Import portable components
            // import * from "../components/api-backend/component.kite"
            // import * from "../components/database/component.kite"

            // // Instantiate imported components
            // ApiBackend myApi {
            //     bucketName = "my-api-data"
            //     dbName = "my-database"
            // }

            // // Use component outputs
            // resource Function consumer {
            //     name = "data-consumer"
            //     runtime = "nodejs20"
            //     environment_vars = {
            //         API_BUCKET = myApi.bucketArn  // From component output
            //     }
            // }

            // When to use resources/ vs components/:
            // - resources/: Quick, simple resource declarations
            // - components/: Reusable patterns with inputs/outputs
            // - cloud/: Cloud-specific resources that can be imported
            """;

        Files.writeString(projectDir.resolve("resources").resolve("main.kite"), mainKite);
        log.debug("Generated sample resource files with composition examples");
    }

    private void generateSampleComponents(Path projectDir) throws IOException {
        // Components organization guide
        var componentsReadme = """
            # Components Directory

            This directory contains **portable, cloud-agnostic components** that can be deployed to any cloud provider.

            ## Import Rules

            **CRITICAL**: Components in this directory MUST NOT import from `cloud/` to maintain portability.

            ### ✅ Allowed Imports
            - Other components: `import * from "../api-backend/component.kite"`
            - Resources from this directory

            ### ❌ Forbidden Imports
            - Cloud-specific resources: `import * from "../../cloud/aws/dynamodb.kite"` ❌
            - This breaks the "write once, deploy anywhere" principle

            ## How Components Work

            1. **Use Generic Resource Types**: Components use abstract types like `Bucket`, `Function`, `Database`
            2. **Automatic Mapping**: kite.yaml maps generic types to cloud-specific resources at deployment time
            3. **Inputs/Outputs**: Components have clear interfaces via input/output declarations
            4. **Cloud-Agnostic**: Same component works on AWS, GCP, or Azure

            ## Example Mappings

            When you use `resource Bucket data {}` in a component:
            - AWS deployment → `AWS::S3::Bucket`
            - GCP deployment → `google.storage.Bucket`
            - Azure deployment → `azure.storage.StorageAccount`

            Mappings are defined in `kite.yaml` under the `mappings` section.

            ## Component Composition

            Components can import and use other components:

            ```kite
            import * from "../database/component.kite"
            import * from "../web-server/component.kite"

            component FullStack app {
                // Use imported components
                Database db {
                    engine = "postgres"
                    instanceClass = "db.t3.small"
                }

                WebServer web {
                    instanceType = "t3.medium"
                    dbEndpoint = db.connectionString
                }
            }
            ```

            ## When to Use Components vs Resources

            - **Components**: Reusable infrastructure patterns with inputs/outputs
            - **Resources** (in `resources/`): Simple standalone resource declarations
            - **Cloud-specific** (in `cloud/`): Provider-specific resources that can import from anywhere
            """;
        Files.writeString(projectDir.resolve("components/README.md"), componentsReadme);

        // API Backend Component (portable, multicloud)
        var apiBackendComponent = """
            // API Backend Component
            // PORTABLE: Uses generic resource types - works on AWS, GCP, or Azure
            // IMPORTANT: This component MUST NOT import from cloud/ to maintain portability

            component ApiBackend api {
                // Input parameters
                input string bucketName
                input string dbName

                // Generic resource types - automatically mapped via kite.yaml
                // Bucket → S3Bucket (AWS), google.storage.Bucket (GCP), StorageAccount (Azure)
                resource Bucket data {
                    name = bucketName
                    versioning = true
                    encryption = true
                }

                // Database → RDS (AWS), CloudSQL (GCP), Azure Database (Azure)
                resource Database db {
                    name = dbName
                    engine = "postgres"
                    version = "14"
                }

                // Function → Lambda (AWS), Cloud Functions (GCP), Azure Functions (Azure)
                resource Function handler {
                    runtime = "nodejs20"
                    memory = 512
                    environment_vars = {
                        BUCKET_NAME = data.name,
                        DB_ENDPOINT = db.endpoint
                    }
                }

                // Output declarations - expose component interface
                output string bucketArn = data.arn
                output string bucketName = data.name
                output string dbEndpoint = db.endpoint
                output string functionArn = handler.arn
            }
            """;
        Files.writeString(projectDir.resolve("components/api-backend/component.kite"), apiBackendComponent);

        var apiBackendReadme = """
            # API Backend Component

            **Type**: Portable, cloud-agnostic component

            ## Description

            A complete API backend with storage, database, and serverless compute.

            ## Portability

            This component uses **generic resource types** that work across all cloud providers:
            - `Bucket` → S3 (AWS), Cloud Storage (GCP), Blob Storage (Azure)
            - `Database` → RDS (AWS), Cloud SQL (GCP), Azure Database (Azure)
            - `Function` → Lambda (AWS), Cloud Functions (GCP), Azure Functions (Azure)

            ## Inputs

            - `bucketName` (string): Name for the storage bucket
            - `dbName` (string): Name for the database instance

            ## Outputs

            - `bucketArn`: ARN/ID of the created bucket
            - `bucketName`: Name of the bucket
            - `dbEndpoint`: Database connection endpoint
            - `functionArn`: ARN/ID of the serverless function

            ## Usage

            ```kite
            import * from "../../components/api-backend/component.kite"

            ApiBackend prodApi {
                bucketName = "prod-api-data"
                dbName = "prod-database"
            }

            // Use outputs
            output string apiEndpoint = prodApi.dbEndpoint
            ```
            """;
        Files.writeString(projectDir.resolve("components/api-backend/README.md"), apiBackendReadme);

        // Web Server Component
        var webServerComponent = """
            // Web Server Component
            // PORTABLE: Uses generic types - works on any cloud provider

            component WebServer server {
                // Input parameters
                input string instanceType
                input string vpcId
                input string[] subnetIds
                input boolean enableMonitoring

                // Generic Compute type - mapped to EC2/Compute Engine/VM
                resource Compute server {
                    type = instanceType
                    network = vpcId
                    subnets = subnetIds
                    monitoring = enableMonitoring
                }

                // Generic SecurityGroup - mapped to Security Group/Firewall/NSG
                resource SecurityGroup sg {
                    vpc_id = vpcId
                    ingress_rules = [
                        {port: 80, protocol: "tcp"},
                        {port: 443, protocol: "tcp"}
                    ]
                }

                // Output declarations
                output string serverId = server.id
                output string publicIp = server.public_ip
                output string securityGroupId = sg.id
            }
            """;
        Files.writeString(projectDir.resolve("components/web-server/component.kite"), webServerComponent);

        var webServerReadme = """
            # Web Server Component

            **Type**: Portable, cloud-agnostic component

            ## Description

            A web server with compute instance and security group configuration.

            ## Inputs

            - `instanceType` (string): Instance size (e.g., "t3.medium", "n1-standard-1")
            - `vpcId` (string): VPC/Network ID
            - `subnetIds` (string[]): List of subnet IDs
            - `enableMonitoring` (boolean): Enable detailed monitoring

            ## Outputs

            - `serverId`: ID of the compute instance
            - `publicIp`: Public IP address of the server
            - `securityGroupId`: ID of the security group
            """;
        Files.writeString(projectDir.resolve("components/web-server/README.md"), webServerReadme);

        // Database Component
        var dbComponent = """
            // Database Component
            // PORTABLE: Works across AWS RDS, GCP Cloud SQL, Azure Database

            component Database db {
                // Input parameters
                input string engine
                input string instanceClass
                input string vpcId
                input number storageSize
                input number backupRetentionDays

                // Generic DatabaseInstance - mapped to RDS/CloudSQL/AzureDB
                resource DatabaseInstance db {
                    engine = engine
                    instance_class = instanceClass
                    storage_gb = storageSize
                    vpc_id = vpcId
                    backup_retention_days = backupRetentionDays
                }

                // Generic DatabaseSubnetGroup
                resource DatabaseSubnetGroup subnetGroup {
                    vpc_id = vpcId
                }

                // Output declarations
                output string connectionString = db.endpoint
                output string databaseId = db.id
                output number port = db.port
            }
            """;
        Files.writeString(projectDir.resolve("components/database/component.kite"), dbComponent);

        var dbReadme = """
            # Database Component

            **Type**: Portable, cloud-agnostic component

            ## Description

            A managed database instance with subnet configuration.

            ## Inputs

            - `engine` (string): Database engine (e.g., "postgres", "mysql")
            - `instanceClass` (string): Instance size
            - `vpcId` (string): VPC/Network ID
            - `storageSize` (number): Storage size in GB
            - `backupRetentionDays` (number): Backup retention period

            ## Outputs

            - `connectionString`: Database connection endpoint
            - `databaseId`: Database instance ID
            - `port`: Database port number
            """;
        Files.writeString(projectDir.resolve("components/database/README.md"), dbReadme);

        log.debug("Generated portable components with documentation");
    }

    private void generateCloudSpecificResources(Path projectDir, String[] providers) throws IOException {
        for (var provider : providers) {
            var cloudDir = projectDir.resolve("cloud/" + provider);

            switch (provider) {
                case "aws" -> {
                    // AWS Route53 (existing)
                    var route53 = """
                        // AWS-Specific: Route53 DNS
                        // Route53HostedZone schema is provided by the AWS provider plugin

                        // Example: Create a hosted zone
                        resource Route53HostedZone mainZone {
                            domain_name = "example.com"
                            comment = "Main hosted zone"
                        }

                        // Example: Private hosted zone
                        // resource Route53HostedZone privateZone {
                        //     domain_name = "internal.example.com"
                        //     vpc_id = "vpc-12345"
                        //     private_zone = true
                        // }
                        """;
                    Files.writeString(cloudDir.resolve("route53.kite"), route53);

                    // AWS DynamoDB (existing)
                    var dynamodb = """
                        // AWS-Specific: DynamoDB Tables
                        // DynamoDBTable schema is provided by the AWS provider plugin
                        // These resource instances can be imported and reused

                        // User sessions table
                        resource DynamoDBTable userTable {
                            table_name = "users"
                            hash_key = "user_id"
                            billing_mode = "PAY_PER_REQUEST"

                            attribute {
                                name = "user_id"
                                type = "S"
                            }
                        }

                        // Orders table with sort key
                        resource DynamoDBTable ordersTable {
                            table_name = "orders"
                            hash_key = "order_id"
                            range_key = "timestamp"
                            billing_mode = "PAY_PER_REQUEST"

                            attribute {
                                name = "order_id"
                                type = "S"
                            }
                            attribute {
                                name = "timestamp"
                                type = "N"
                            }
                        }
                        """;
                    Files.writeString(cloudDir.resolve("dynamodb.kite"), dynamodb);

                    // AWS S3 Storage (NEW - importable resource instances)
                    var storage = """
                        // AWS-Specific: S3 Buckets
                        // S3Bucket schema is provided by the AWS provider plugin
                        // These resource instances can be imported and reused across stacks

                        // Static assets bucket (public)
                        resource S3Bucket staticAssets {
                            bucket_name = "static-assets"
                            versioning = false
                            encryption = true

                            public_access_block {
                                block_public_acls = false
                                ignore_public_acls = false
                            }

                            cors_rule {
                                allowed_methods = ["GET", "HEAD"]
                                allowed_origins = ["*"]
                                max_age_seconds = 3600
                            }
                        }

                        // Application data bucket (private)
                        resource S3Bucket appData {
                            bucket_name = "app-data"
                            versioning = true
                            encryption = true

                            lifecycle_rule {
                                enabled = true
                                transition {
                                    days = 90
                                    storage_class = "GLACIER"
                                }
                            }
                        }

                        // Logs bucket
                        resource S3Bucket logs {
                            bucket_name = "application-logs"
                            versioning = false
                            encryption = true

                            lifecycle_rule {
                                enabled = true
                                expiration {
                                    days = 30
                                }
                            }
                        }
                        """;
                    Files.writeString(cloudDir.resolve("storage.kite"), storage);

                    // AWS Serverless API Component (NEW - cloud-specific with imports)
                    var serverlessComponent = """
                        // AWS Serverless API Component
                        // CLOUD-SPECIFIC: Uses AWS-specific resources and can import from cloud/aws/
                        // This component demonstrates import patterns and cloud-specific composition

                        // Import cloud-specific resources
                        import * from "../dynamodb.kite"
                        import * from "../storage.kite"

                        // Import portable components (allowed)
                        import * from "../../components/api-backend/component.kite"

                        component AwsServerlessApi api {
                            // Input parameters
                            input string apiName
                            input string stage

                            // AWS-specific Lambda function
                            resource Lambda apiHandler {
                                function_name = "${apiName}-handler"
                                runtime = "nodejs20.x"
                                memory_size = 512
                                timeout = 30

                                environment {
                                    variables = {
                                        STAGE = stage
                                        USER_TABLE = userTable.name        // From dynamodb.kite import
                                        ORDERS_TABLE = ordersTable.name    // From dynamodb.kite import
                                        DATA_BUCKET = appData.id           // From storage.kite import
                                        LOGS_BUCKET = logs.id              // From storage.kite import
                                    }
                                }
                            }

                            // AWS API Gateway
                            resource ApiGatewayRestApi api {
                                name = apiName
                                description = "Serverless API for ${apiName}"
                            }

                            resource ApiGatewayDeployment deployment {
                                rest_api_id = api.id
                                stage_name = stage
                            }

                            // Grant Lambda permissions to DynamoDB
                            resource LambdaPermission dynamodbAccess {
                                function_name = apiHandler.function_name
                                principal = "dynamodb.amazonaws.com"
                            }

                            // Output declarations
                            output string functionArn = apiHandler.arn
                            output string apiEndpoint = api.execution_arn
                            output string invokeUrl = "${api.id}.execute-api.${stage}.amazonaws.com"
                        }
                        """;
                    Files.writeString(cloudDir.resolve("serverless-api/component.kite"), serverlessComponent);

                    var serverlessReadme = """
                        # AWS Serverless API Component

                        **Type**: Cloud-specific (AWS only)

                        ## Description

                        An AWS-native serverless API using Lambda, API Gateway, and DynamoDB.

                        ## Import Pattern

                        This component demonstrates how cloud-specific components can import:

                        1. **Cloud-specific resources** from same provider:
                           ```kite
                           import * from "../dynamodb.kite"
                           import * from "../storage.kite"
                           ```

                        2. **Portable components** (for composition):
                           ```kite
                           import * from "../../components/api-backend/component.kite"
                           ```

                        ## Portability

                        This component is **NOT portable** - it uses AWS-specific resources:
                        - `Lambda` (AWS Lambda functions)
                        - `ApiGatewayRestApi` (AWS API Gateway)
                        - `DynamoDBTable` (via import)
                        - `S3Bucket` (via import)

                        ## Inputs

                        - `apiName` (string): Name for the API
                        - `stage` (string): Deployment stage (dev, staging, prod)

                        ## Outputs

                        - `functionArn`: ARN of the Lambda function
                        - `apiEndpoint`: API Gateway execution ARN
                        - `invokeUrl`: Full URL to invoke the API

                        ## Usage

                        ```kite
                        import * from "../../cloud/aws/serverless-api/component.kite"

                        AwsServerlessApi prodApi {
                            apiName = "production-api"
                            stage = "prod"
                        }

                        // Use outputs
                        output string apiUrl = prodApi.invokeUrl
                        ```

                        ## Environment Stack Example

                        In `environments/prod/Backend.kite`:

                        ```kite
                        import * from "../../cloud/aws/serverless-api/component.kite"

                        // Deploy AWS-specific serverless API
                        AwsServerlessApi api {
                            apiName = "prod-api"
                            stage = "prod"
                        }
                        ```
                        """;
                    Files.writeString(cloudDir.resolve("serverless-api/README.md"), serverlessReadme);
                }
                case "gcp" -> {
                    var cloudRun = """
                        // GCP-Specific: Cloud Run
                        // CloudRunService schema is provided by the GCP provider plugin

                        // Example: Deploy a Cloud Run service
                        resource CloudRunService api {
                            name = "my-api"
                            image = "gcr.io/project/api:latest"
                            region = "us-central1"
                            memory = "512Mi"
                            cpu = 1
                            max_instances = 10
                        }

                        // Example: Private Cloud Run service
                        // resource CloudRunService internalApi {
                        //     name = "internal-api"
                        //     image = "gcr.io/project/internal:latest"
                        //     region = "us-central1"
                        //     ingress = "internal"
                        // }
                        """;
                    Files.writeString(cloudDir.resolve("cloud-run.kite"), cloudRun);

                    var firestore = """
                        // GCP-Specific: Firestore
                        // FirestoreDatabase schema is provided by the GCP provider plugin

                        // Example: Create Firestore database
                        resource FirestoreDatabase mainDb {
                            database_id = "main"
                            location = "us-central"
                            type = "FIRESTORE_NATIVE"
                        }

                        // Example: Multi-region Firestore
                        // resource FirestoreDatabase prodDb {
                        //     database_id = "production"
                        //     location = "nam5"
                        //     type = "FIRESTORE_NATIVE"
                        // }
                        """;
                    Files.writeString(cloudDir.resolve("firestore.kite"), firestore);
                }
                case "azure" -> {
                    var cosmosDb = """
                        // Azure-Specific: Cosmos DB
                        // CosmosDBAccount schema is provided by the Azure provider plugin

                        // Example: Create Cosmos DB account
                        resource CosmosDBAccount mainDb {
                            account_name = "my-cosmos-db"
                            location = "eastus"
                            consistency_level = "Session"
                            offer_type = "Standard"
                        }

                        // Example: Globally distributed Cosmos DB
                        // resource CosmosDBAccount globalDb {
                        //     account_name = "global-cosmos-db"
                        //     location = "eastus"
                        //     consistency_level = "BoundedStaleness"
                        //     geo_locations = ["eastus", "westus", "westeurope"]
                        // }
                        """;
                    Files.writeString(cloudDir.resolve("cosmos-db.kite"), cosmosDb);

                    var appService = """
                        // Azure-Specific: App Service
                        // AppService schema is provided by the Azure provider plugin

                        // Example: Create App Service
                        resource AppService webApp {
                            name = "my-web-app"
                            location = "eastus"
                            sku_tier = "Standard"
                            sku_size = "S1"
                        }

                        // Example: Linux App Service with container
                        // resource AppService containerApp {
                        //     name = "my-container-app"
                        //     location = "eastus"
                        //     os_type = "Linux"
                        //     container_image = "myregistry.azurecr.io/myapp:latest"
                        // }
                        """;
                    Files.writeString(cloudDir.resolve("app-service.kite"), appService);
                }
            }
        }

        log.debug("Generated cloud-specific resources for: {}", String.join(", ", providers));
    }

    private void generateModules(Path projectDir) throws IOException {
        // Modules README
        var modulesReadme = """
            # Modules Directory

            This directory contains **application-level compositions** that combine multiple components into cohesive applications.

            ## What are Modules?

            Modules are higher-level abstractions that:
            - **Compose multiple components** together into complete applications
            - **Can be portable OR cloud-specific** (depending on what they import)
            - **Have inputs/outputs** for parameterization
            - **Represent business applications** (e.g., "backend-api", "frontend-app", "data-pipeline")

            ## Hierarchy

            ```
            Resources     → Simple standalone resources (lowest level)
            Components    → Reusable infrastructure patterns (mid level)
            Modules       → Application compositions (high level)
            Environments  → Deployment targets (orchestration)
            ```

            ## Import Rules for Modules

            Modules can import:
            - ✅ Components (from `components/`)
            - ✅ Other modules (composition)
            - ✅ Cloud-specific resources (from `cloud/`) - but this makes the module cloud-specific
            - ✅ Resources (from `resources/`)

            **Portability**: If a module imports from `cloud/`, it becomes cloud-specific and won't work on other providers.

            ## Example: Portable Module

            ```kite
            // modules/backend-api/module.kite
            import * from "../../components/api-backend/component.kite"
            import * from "../../components/database/component.kite"

            // This module is PORTABLE - works on any cloud
            component BackendApiModule api {
                input string environment
                input string dbSize

                // Compose portable components
                ApiBackend backend {
                    bucketName = "${environment}-api-data"
                    dbName = "${environment}-db"
                }

                Database db {
                    engine = "postgres"
                    instanceClass = dbSize
                    vpcId = "vpc-12345"
                    storageSize = 100
                    backupRetentionDays = 7
                }

                // Module outputs
                output string apiEndpoint = backend.bucketArn
                output string dbConnection = db.connectionString
            }
            ```

            ## Example: Cloud-Specific Module

            ```kite
            // modules/aws-serverless-app/module.kite
            import * from "../../components/api-backend/component.kite"
            import * from "../../cloud/aws/dynamodb.kite"
            import * from "../../cloud/aws/serverless-api/component.kite"

            // This module is AWS-SPECIFIC due to cloud/ imports
            component AwsServerlessAppModule app {
                input string appName

                // AWS-specific serverless API
                AwsServerlessApi api {
                    apiName = appName
                    stage = "prod"
                }

                // Can reference AWS-specific resources
                output string dynamoTable = userTable.name
                output string apiUrl = api.invokeUrl
            }
            ```

            ## When to Use Modules vs Components

            - **Components**: Reusable infrastructure patterns (database, web-server, API backend)
            - **Modules**: Complete applications that combine components (full backend API, frontend app, data pipeline)

            Use modules when you want to package a complete application that can be deployed as a unit.
            """;
        Files.writeString(projectDir.resolve("modules/README.md"), modulesReadme);

        // Backend API Module (portable)
        var backendApiModule = """
            // Backend API Module
            // PORTABLE: Composes portable components - works on any cloud

            // Import portable components
            import * from "../../components/api-backend/component.kite"
            import * from "../../components/database/component.kite"

            component BackendApiModule api {
                // Input parameters
                input string environment
                input string dbSize
                input string instanceSize

                // Compose API backend component
                ApiBackend backend {
                    bucketName = "${environment}-api-data"
                    dbName = "${environment}-database"
                }

                // Compose database component
                Database db {
                    engine = "postgres"
                    instanceClass = dbSize
                    vpcId = "vpc-12345"
                    storageSize = environment == "prod" ? 200 : 50
                    backupRetentionDays = environment == "prod" ? 14 : 7
                }

                // Module-level resource using component outputs
                resource Function scheduler {
                    name = "${environment}-scheduler"
                    runtime = "nodejs20"
                    memory = 256
                    environment_vars = {
                        API_BUCKET = backend.bucketArn
                        DB_ENDPOINT = db.connectionString
                    }
                }

                // Module outputs - expose composed component outputs
                output string apiBucketArn = backend.bucketArn
                output string apiFunctionArn = backend.functionArn
                output string dbConnectionString = db.connectionString
                output string schedulerArn = scheduler.arn
            }
            """;
        Files.writeString(projectDir.resolve("modules/backend-api/module.kite"), backendApiModule);

        var backendApiReadme = """
            # Backend API Module

            **Type**: Portable, cloud-agnostic module

            ## Description

            A complete backend API application that composes:
            - API backend component (storage, database, serverless compute)
            - Database component (managed database with backups)
            - Scheduler function (background jobs)

            ## Portability

            This module is **fully portable** - it only imports from `components/` and uses generic resource types.
            Works on AWS, GCP, or Azure.

            ## Inputs

            - `environment` (string): Environment name (dev, staging, prod)
            - `dbSize` (string): Database instance size (e.g., "db.t3.small")
            - `instanceSize` (string): Compute instance size

            ## Outputs

            - `apiBucketArn`: ARN/ID of the API data bucket
            - `apiFunctionArn`: ARN/ID of the API handler function
            - `dbConnectionString`: Database connection string
            - `schedulerArn`: ARN/ID of the scheduler function

            ## Usage

            ```kite
            // In environments/prod/Backend.kite
            import * from "../../modules/backend-api/module.kite"

            BackendApiModule prodBackend {
                environment = "prod"
                dbSize = "db.m5.large"
                instanceSize = "m5.large"
            }

            // Use module outputs
            output string apiEndpoint = prodBackend.dbConnectionString
            ```
            """;
        Files.writeString(projectDir.resolve("modules/backend-api/README.md"), backendApiReadme);

        // Frontend App Module (portable)
        var frontendAppModule = """
            // Frontend App Module
            // PORTABLE: Uses generic components - works on any cloud

            // Import portable components
            import * from "../../components/web-server/component.kite"

            component FrontendAppModule app {
                // Input parameters
                input string environment
                input string instanceType
                input string domainName

                // Web server component for frontend
                WebServer frontend {
                    instanceType = instanceType
                    vpcId = "vpc-12345"
                    subnetIds = ["subnet-1", "subnet-2"]
                    enableMonitoring = environment == "prod" ? true : false
                }

                // Static assets bucket (generic)
                resource Bucket staticAssets {
                    name = "${environment}-static-assets"
                    versioning = false
                    encryption = true
                }

                // CDN distribution (generic)
                resource CDN cdn {
                    name = "${environment}-cdn"
                    origin_domain = staticAssets.domain_name
                    enabled = true
                }

                // Module outputs
                output string frontendIp = frontend.publicIp
                output string cdnDomain = cdn.domain_name
                output string assetsBucket = staticAssets.name
            }
            """;
        Files.writeString(projectDir.resolve("modules/frontend-app/module.kite"), frontendAppModule);

        var frontendAppReadme = """
            # Frontend App Module

            **Type**: Portable, cloud-agnostic module

            ## Description

            A complete frontend application that includes:
            - Web server component (compute + security groups)
            - Static assets storage
            - CDN distribution

            ## Portability

            This module is **fully portable** - uses generic resource types that work on any cloud.

            ## Inputs

            - `environment` (string): Environment name (dev, staging, prod)
            - `instanceType` (string): Instance size for web server
            - `domainName` (string): Domain name for the application

            ## Outputs

            - `frontendIp`: Public IP of the frontend server
            - `cdnDomain`: CDN domain name for static assets
            - `assetsBucket`: Name of the static assets bucket

            ## Usage

            ```kite
            // In environments/prod/Frontend.kite
            import * from "../../modules/frontend-app/module.kite"

            FrontendAppModule prodFrontend {
                environment = "prod"
                instanceType = "m5.large"
                domainName = "example.com"
            }

            // Use module outputs
            output string websiteUrl = "https://${prodFrontend.cdnDomain}"
            ```
            """;
        Files.writeString(projectDir.resolve("modules/frontend-app/README.md"), frontendAppReadme);

        log.debug("Generated application modules");
    }

    private void generateEnvExample(Path projectDir) throws IOException {
        var envExample = """
            # Kite Environment Variables Example
            # Copy this file to .env and fill in your actual values
            # All Kite configuration uses the KITE_ prefix

            # ============================================================================
            # STATE BACKEND (PostgreSQL)
            # ============================================================================
            KITE_DB_HOST=localhost
            KITE_DB_PORT=5432
            KITE_DB_NAME=kite_state
            KITE_DB_USER=kite
            KITE_DB_PASSWORD=your-secure-password-here
            KITE_DB_SCHEMA=public

            # ============================================================================
            # AWS CONFIGURATION
            # ============================================================================
            KITE_AWS_PROFILE=default
            KITE_AWS_REGION=us-east-1
            KITE_AWS_ACCOUNT_ID=123456789012

            # Optional: Use explicit AWS credentials instead of profile
            # KITE_AWS_ACCESS_KEY_ID=your-access-key
            # KITE_AWS_SECRET_ACCESS_KEY=your-secret-key

            # ============================================================================
            # GCP CONFIGURATION
            # ============================================================================
            KITE_GCP_PROJECT=my-gcp-project
            KITE_GCP_REGION=us-central1
            KITE_GCP_CREDENTIALS_FILE=/path/to/service-account.json

            # Optional: Use Application Default Credentials (ADC)
            # Leave KITE_GCP_CREDENTIALS_FILE empty to use ADC

            # ============================================================================
            # AZURE CONFIGURATION
            # ============================================================================
            KITE_AZURE_SUBSCRIPTION_ID=your-subscription-id
            KITE_AZURE_TENANT_ID=your-tenant-id
            KITE_AZURE_CLIENT_ID=your-client-id
            KITE_AZURE_CLIENT_SECRET=your-client-secret
            KITE_AZURE_RESOURCE_GROUP=my-resource-group
            KITE_AZURE_LOCATION=eastus

            # ============================================================================
            # OPTIONAL SETTINGS
            # ============================================================================

            # Logging level (DEBUG, INFO, WARN, ERROR)
            # KITE_LOG_LEVEL=INFO

            # Dry run mode - preview changes without applying
            # KITE_DRY_RUN=false

            # Auto-approve changes (skip confirmation prompts)
            # KITE_AUTO_APPROVE=false

            # Maximum parallel operations
            # KITE_MAX_PARALLELISM=10
            """;

        Files.writeString(projectDir.resolve(".env.example"), envExample);
        log.debug("Generated .env.example");
    }

    private boolean isDirectoryEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return true;
        }

        try (var entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }
}
