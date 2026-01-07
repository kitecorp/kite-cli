package cloud.kitelang.cli.util;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * Central factory for Jackson mappers.
 * Provides shared, pre-configured mapper instances to avoid duplication.
 */
public final class JacksonMappers {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .build();

    private JacksonMappers() {}

    /**
     * Returns a shared JSON mapper instance.
     */
    public static JsonMapper json() {
        return JSON_MAPPER;
    }

    /**
     * Returns a shared YAML mapper instance.
     * Configured with:
     * - No document start marker (---)
     * - Minimized quotes
     */
    public static YAMLMapper yaml() {
        return YAML_MAPPER;
    }
}
