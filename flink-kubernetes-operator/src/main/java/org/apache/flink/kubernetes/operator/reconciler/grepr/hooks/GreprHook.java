package org.apache.flink.kubernetes.operator.reconciler.grepr.hooks;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.operator.api.spec.AbstractFlinkSpec;
import org.apache.flink.kubernetes.operator.config.FlinkOperatorConfiguration;
import org.apache.flink.kubernetes.operator.service.FlinkService;

/** Grepr hooks are used to execute custom logic during resource reconciliation. */
public interface GreprHook {

    GreprHookStatus getStatusOrExecute(
            Configuration currentConfig,
            Configuration deployConfig,
            String jobName,
            AbstractFlinkSpec lastReconciledSpec,
            FlinkService flinkService,
            FlinkOperatorConfiguration operatorConfig)
            throws Exception;
}
