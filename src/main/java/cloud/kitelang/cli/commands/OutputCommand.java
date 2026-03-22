package cloud.kitelang.cli.commands;

import cloud.kitelang.cli.console.Console;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Displays output values from deployed infrastructure.
 * Outputs are values exported from components and stacks (e.g., URLs, ARNs, IPs).
 */
@Command(
    name = "output",
    description = {
        "Display output values from deployed infrastructure",
        "",
        "Shows the output values that were exported from your components",
        "and stacks after 'kite apply'. Common outputs include:",
        "  - API endpoints and URLs",
        "  - Resource ARNs and IDs",
        "  - Connection strings",
        "  - IP addresses"
    },
    footer = {
        "",
        "Examples:",
        "  kite output -e dev                    Show all outputs for dev",
        "  kite output api_url -e dev            Show specific output value",
        "  kite output -e prod --json            Output as JSON",
        "  kite output db_url -e dev --raw       Raw value only (for scripting)",
        "  kite output -e dev -s Backend         Show outputs from Backend stack",
        "  kite output -e dev --sensitive        Include sensitive values"
    },
    mixinStandardHelpOptions = true
)
@Slf4j
public class OutputCommand implements Callable<Integer> {

    @Parameters(
        index = "0",
        paramLabel = "name",
        description = "Specific output name to display (shows all if omitted)",
        arity = "0..1"
    )
    private String outputName;

    @Option(
        names = {"-e", "--environment"},
        paramLabel = "env",
        description = "Target environment (dev, staging, prod)",
        defaultValue = "dev"
    )
    private String environment;

    @Option(
        names = {"-s", "--stack"},
        paramLabel = "stack",
        description = "Stack name (e.g., Backend, Frontend)"
    )
    private String stack;

    @Option(
        names = {"--json"},
        description = "Output in JSON format"
    )
    private boolean jsonOutput;

    @Option(
        names = {"--raw"},
        description = "Output raw value only (for scripting)"
    )
    private boolean rawOutput;

    @Option(
        names = {"--sensitive"},
        description = "Show sensitive values (hidden by default)"
    )
    private boolean showSensitive;

    @Override
    public Integer call() {
        try {
            log.info("Fetching outputs for environment: {}", environment);

            // TODO: Load outputs from state backend
            var outputs = loadOutputs();

            if (outputs.isEmpty()) {
                Console.println("No outputs found for environment: " + environment);
                return 0;
            }

            // Filter to specific output if requested
            if (outputName != null) {
                return displaySingleOutput(outputs);
            }

            // Display all outputs
            return displayAllOutputs(outputs);

        } catch (Exception e) {
            log.error("Failed to fetch outputs", e);
            Console.error(e.getMessage());
            return 1;
        }
    }

    /**
     * Loads outputs from state backend.
     * TODO: Integrate with actual state storage.
     */
    private Map<String, OutputValue> loadOutputs() {
        // Placeholder outputs - would come from state backend
        var outputs = new LinkedHashMap<String, OutputValue>();

        if (stack == null || "Backend".equalsIgnoreCase(stack)) {
            outputs.put("api_endpoint", new OutputValue(
                "https://api.example.com/v1",
                "string",
                false,
                "API Gateway endpoint URL"
            ));
            outputs.put("bucket_arn", new OutputValue(
                "arn:aws:s3:::" + environment + "-api-data",
                "string",
                false,
                "S3 bucket ARN for API data"
            ));
            outputs.put("db_connection_string", new OutputValue(
                "postgres://user:***@db.example.com:5432/app",
                "string",
                true,
                "Database connection string"
            ));
            outputs.put("function_arn", new OutputValue(
                "arn:aws:lambda:us-east-1:123456789:function:" + environment + "-handler",
                "string",
                false,
                "Lambda function ARN"
            ));
        }

        if (stack == null || "Frontend".equalsIgnoreCase(stack)) {
            outputs.put("cdn_url", new OutputValue(
                "https://d1234567890.cloudfront.net",
                "string",
                false,
                "CloudFront distribution URL"
            ));
            outputs.put("server_ip", new OutputValue(
                "54.123.45.67",
                "string",
                false,
                "Web server public IP"
            ));
        }

        return outputs;
    }

    /**
     * Displays a single output value.
     */
    private int displaySingleOutput(Map<String, OutputValue> outputs) {
        var output = outputs.get(outputName);

        if (output == null) {
            Console.error("Output not found: " + outputName);
            Console.println("Available outputs: " + String.join(", ", outputs.keySet()));
            return 1;
        }

        var value = output.getValue(showSensitive);

        if (rawOutput) {
            Console.println(value);
        } else if (jsonOutput) {
            Console.printf("""
                {
                  "name": "%s",
                  "value": "%s",
                  "type": "%s",
                  "sensitive": %s
                }
                """, outputName, value, output.type(), output.sensitive());
        } else {
            Console.println(outputName + " = " + value);
        }

        return 0;
    }

    /**
     * Displays all outputs.
     */
    private int displayAllOutputs(Map<String, OutputValue> outputs) {
        if (jsonOutput) {
            printJsonOutputs(outputs);
        } else {
            printTextOutputs(outputs);
        }
        return 0;
    }

    /**
     * Prints outputs in text format.
     */
    private void printTextOutputs(Map<String, OutputValue> outputs) {
        // Find max key length for alignment
        var maxKeyLen = outputs.keySet().stream()
            .mapToInt(String::length)
            .max()
            .orElse(20);

        Console.println("Outputs for environment: " + environment);
        if (stack != null) {
            Console.println("Stack: " + stack);
        }
        Console.println();

        for (var entry : outputs.entrySet()) {
            var name = entry.getKey();
            var output = entry.getValue();
            var value = output.getValue(showSensitive);

            var paddedName = String.format("%-" + maxKeyLen + "s", name);
            Console.println(paddedName + " = " + value);

            if (output.description() != null && !output.description().isBlank()) {
                Console.println(String.format("%" + maxKeyLen + "s   # %s", "", output.description()));
            }
        }
    }

    /**
     * Prints outputs in JSON format.
     */
    private void printJsonOutputs(Map<String, OutputValue> outputs) {
        var sb = new StringBuilder();
        sb.append("{\n");

        var entries = outputs.entrySet().toArray(Map.Entry[]::new);
        for (var i = 0; i < entries.length; i++) {
            var name = (String) entries[i].getKey();
            var output = (OutputValue) entries[i].getValue();
            var value = output.getValue(showSensitive);

            sb.append(String.format("  \"%s\": {\n", name));
            sb.append(String.format("    \"value\": \"%s\",\n", escapeJson(value)));
            sb.append(String.format("    \"type\": \"%s\",\n", output.type()));
            sb.append(String.format("    \"sensitive\": %s\n", output.sensitive()));
            sb.append("  }");

            if (i < entries.length - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("}\n");
        Console.print(sb.toString());
    }

    /**
     * Escapes special characters for JSON.
     */
    private String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * Represents an output value with metadata.
     */
    private record OutputValue(String value, String type, boolean sensitive, String description) {

        /**
         * Gets the value, masking if sensitive and not allowed to show.
         */
        String getValue(boolean showSensitive) {
            if (sensitive && !showSensitive) {
                return "<sensitive>";
            }
            return value;
        }
    }
}
