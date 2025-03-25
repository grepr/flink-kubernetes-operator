package org.apache.flink.kubernetes.operator.hooks;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.operator.TestUtils;
import org.apache.flink.kubernetes.operator.config.FlinkConfigManager;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test for {@link FlinkResourceHookUtils}. */
public class FlinkResourceHookUtilsTest {

    @TempDir public Path temporaryFolder;

    @Test
    @SneakyThrows
    public void testDiscoverHooks() {
        Map<String, String> originalEnv = System.getenv();
        try {
            Map<String, String> systemEnv = new HashMap<>(originalEnv);
            systemEnv.put(
                    ConfigConstants.ENV_FLINK_PLUGINS_DIR,
                    TestUtils.getTestPluginsRootDir(temporaryFolder));
            TestUtils.setEnv(systemEnv);
            assertEquals(
                    new HashSet<>(List.of(TestHook.class.getName())),
                    FlinkResourceHookUtils.discoverHooks(
                                    new FlinkConfigManager(new Configuration()))
                            .stream()
                            .map(v -> v.getClass().getName())
                            .collect(Collectors.toSet()));
        } finally {
            TestUtils.setEnv(originalEnv);
        }
    }
}
