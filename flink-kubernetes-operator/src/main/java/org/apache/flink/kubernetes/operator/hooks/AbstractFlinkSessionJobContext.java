package org.apache.flink.kubernetes.operator.hooks;

import org.apache.flink.api.common.JobID;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.operator.api.spec.FlinkSessionJobSpec;
import org.apache.flink.kubernetes.operator.config.FlinkOperatorConfiguration;
import org.apache.flink.kubernetes.operator.hooks.flink.FlinkCluster;
import org.apache.flink.kubernetes.operator.service.FlinkService;
import org.apache.flink.runtime.messages.webmonitor.MultipleJobsDetails;
import org.apache.flink.runtime.rest.handler.legacy.messages.ClusterOverviewWithVersion;

import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.concurrent.TimeUnit;

import static org.apache.flink.kubernetes.operator.config.FlinkConfigBuilder.FLINK_VERSION;

/** Base class for flink session job context. */
public abstract class AbstractFlinkSessionJobContext
        implements FlinkResourceHook.FlinkResourceHookContext {

    public abstract FlinkService getFlinkService();

    public abstract FlinkOperatorConfiguration getOperatorConfig();

    @Override
    public FlinkCluster getFlinkSessionCluster() {
        return new FlinkCluster() {
            @Override
            public JobID submitJob(FlinkSessionJobSpec spec) throws Exception {
                var deployConfig = getDeployConfig();
                spec.setDeploymentName(deployConfig.get(KubernetesConfigOptions.CLUSTER_ID));
                var objectMeta = new ObjectMeta();
                objectMeta.setNamespace(deployConfig.get(KubernetesConfigOptions.NAMESPACE));
                var conf = getFlinkSessionJobConfig(deployConfig);
                return getFlinkService()
                        .submitJobToSessionCluster(objectMeta, spec, JobID.generate(), conf, null);
            }

            @Override
            public ClusterOverviewWithVersion getClusterOverview() {
                try (var client = getFlinkService().getClusterClient(getDeployConfig())) {
                    return client.getClusterOverview()
                            .get(
                                    getOperatorConfig().getFlinkClientTimeout().toMillis(),
                                    TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public MultipleJobsDetails getJobs() {
                return getFlinkService().getJobs(getDeployConfig());
            }

            @Override
            public void cancelJob(JobID jobId) {
                try (var client = getFlinkService().getClusterClient(getDeployConfig())) {
                    client.cancel(jobId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            private Configuration getFlinkSessionJobConfig(Configuration deployConfig) {
                var conf = new Configuration();
                conf.set(
                        KubernetesConfigOptions.CLUSTER_ID,
                        deployConfig.get(KubernetesConfigOptions.CLUSTER_ID));
                conf.set(
                        KubernetesConfigOptions.NAMESPACE,
                        deployConfig.get(KubernetesConfigOptions.NAMESPACE));
                conf.set(
                        KubernetesConfigOptions.KUBERNETES_SERVICE_ACCOUNT,
                        deployConfig.get(KubernetesConfigOptions.KUBERNETES_SERVICE_ACCOUNT));
                conf.set(FLINK_VERSION, deployConfig.get(FLINK_VERSION));
                return conf;
            }
        };
    }
}
