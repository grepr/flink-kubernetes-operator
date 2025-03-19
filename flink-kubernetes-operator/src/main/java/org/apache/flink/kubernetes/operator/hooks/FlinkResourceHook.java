package org.apache.flink.kubernetes.operator.hooks;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.plugin.Plugin;
import org.apache.flink.kubernetes.operator.api.FlinkSessionJob;
import org.apache.flink.kubernetes.operator.hooks.flink.FlinkCluster;

import io.fabric8.kubernetes.client.KubernetesClient;

/** Hook for executing custom logic during flink resource reconciliation. */
public interface FlinkResourceHook extends Plugin {

    /**
     * Get the name of the hook.
     *
     * @return Name of the hook.
     */
    String getName();

    /**
     * Get the status of the hook or execute the hook.
     *
     * @return Status of the hook.
     */
    FlinkResourceHookStatus execute(FlinkResourceHookContext context);

    /**
     * Gets the hook type for which the hook is applicable.
     *
     * @return The hook type.
     */
    FlinkResourceHookType getHookType();

    /** Context for the {@link FlinkResourceHook}. */
    interface FlinkResourceHookContext {
        FlinkSessionJob getFlinkSessionJob();

        FlinkCluster getFlinkSessionCluster();

        Configuration getDeployConfig();

        Configuration getCurrentDeployedConfig();

        KubernetesClient getKubernetesClient();
    }
}
