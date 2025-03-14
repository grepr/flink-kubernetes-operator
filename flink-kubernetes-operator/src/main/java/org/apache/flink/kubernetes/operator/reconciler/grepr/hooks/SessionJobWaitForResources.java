package org.apache.flink.kubernetes.operator.reconciler.grepr.hooks;

import org.apache.flink.api.common.JobStatus;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.operator.api.spec.AbstractFlinkSpec;
import org.apache.flink.kubernetes.operator.api.spec.FlinkSessionJobSpec;
import org.apache.flink.kubernetes.operator.config.FlinkOperatorConfiguration;
import org.apache.flink.kubernetes.operator.reconciler.grepr.ArtificiallyInducedSlotProvisioner;
import org.apache.flink.kubernetes.operator.service.FlinkService;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.messages.webmonitor.JobDetails;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Hook that waits for resources to be available for a session job on scale-ups. */
@RequiredArgsConstructor
public class SessionJobWaitForResources implements GreprHook {

    private static final Logger LOG = LoggerFactory.getLogger(SessionJobWaitForResources.class);
    private static final List<ExecutionState> PENDING_STATES =
            List.of(
                    ExecutionState.CREATED,
                    ExecutionState.SCHEDULED,
                    ExecutionState.DEPLOYING,
                    ExecutionState.RECONCILING,
                    ExecutionState.INITIALIZING);

    private final ArtificiallyInducedSlotProvisioner provisioner;

    @Override
    public GreprHookStatus getStatusOrExecute(
            Configuration currentConfig,
            Configuration deployConfig,
            String jobName,
            AbstractFlinkSpec lastReconciledSpec,
            FlinkService flinkService,
            FlinkOperatorConfiguration operatorConfig)
            throws Exception {
        if (!(lastReconciledSpec instanceof FlinkSessionJobSpec)) {
            return GreprHookStatus.NOT_APPLICABLE;
        }

        var sessionJobSpec = (FlinkSessionJobSpec) lastReconciledSpec;
        var oldParallelism = getMaxParallelism(currentConfig, sessionJobSpec);
        var newParallelism = getMaxParallelism(deployConfig, sessionJobSpec);

        if (oldParallelism < newParallelism) {
            LOG.info(
                    "Provisioning new resources. Current parallelism: {} < new parallelism: {}",
                    oldParallelism,
                    newParallelism);

            var conf = new Configuration();
            conf.set(
                    KubernetesConfigOptions.CLUSTER_ID,
                    deployConfig.get(KubernetesConfigOptions.CLUSTER_ID));
            conf.set(
                    KubernetesConfigOptions.NAMESPACE,
                    deployConfig.get(KubernetesConfigOptions.NAMESPACE));
            var jobs = flinkService.getJobs(conf);
            var dummyJobs =
                    jobs.getJobs().stream()
                            .filter(
                                    j ->
                                            !j.getJobName().equals(jobName)
                                                    && j.getJobName().startsWith("dummy"))
                            .collect(Collectors.toList());
            if (dummyJobs.stream().anyMatch(this::isJobPending)) {
                LOG.info("Waiting for dummy jobs to finish, returning PENDING");
                return GreprHookStatus.PENDING;
            }

            if (dummyJobs.stream().anyMatch(job -> job.getStatus().isTerminalState())) {
                LOG.info("Dummy jobs failed, returning FAILED");
                return GreprHookStatus.FAILED;
            }

            var cancelledRunningJobs = false;
            for (var job : dummyJobs) {
                LOG.info("Tasks per state: {}", Arrays.toString(job.getTasksPerState()));
                if (job.getStatus() == JobStatus.RUNNING) {
                    LOG.info("Cancelling running dummy job {}", job.getJobId());
                    flinkService
                            .getClusterClient(conf)
                            .cancel(job.getJobId())
                            .get(
                                    operatorConfig.getFlinkClientTimeout().toMillis(),
                                    TimeUnit.MILLISECONDS);
                    cancelledRunningJobs = true;
                }
            }

            if (cancelledRunningJobs) {
                LOG.info("Cancelled running dummy jobs, returning COMPLETED");
                return GreprHookStatus.COMPLETED;
            }

            var diff = newParallelism - oldParallelism;
            var numSlotsAvailable =
                    flinkService
                            .getClusterClient(conf)
                            .getClusterOverview()
                            .get(
                                    operatorConfig.getFlinkClientTimeout().toMillis(),
                                    TimeUnit.MILLISECONDS)
                            .getNumSlotsAvailable();
            if (numSlotsAvailable >= diff) {
                return GreprHookStatus.NOT_APPLICABLE;
            }
            LOG.info("Provisioning {} new slots", diff);
            provisioner.provisionSlots(diff, flinkService, deployConfig, jobName);
            return GreprHookStatus.PENDING;
        } else {
            LOG.info(
                    "No need to provision new resources. Current parallelism: {} >= new parallelism: {}",
                    oldParallelism,
                    newParallelism);
            return GreprHookStatus.NOT_APPLICABLE;
        }
    }

    private boolean isJobPending(JobDetails job) {
        if (job.getStatus() == JobStatus.INITIALIZING
                || job.getStatus() == JobStatus.CREATED
                || job.getStatus() == JobStatus.RECONCILING) {
            return true;
        }
        return PENDING_STATES.stream()
                .anyMatch(executionState -> job.getTasksPerState()[executionState.ordinal()] > 0);
    }

    private Integer getMaxParallelism(
            Configuration config, FlinkSessionJobSpec lastReconciledSpec) {
        if (!config.containsKey(PipelineOptions.PARALLELISM_OVERRIDES.key())) {
            return lastReconciledSpec.getJob().getParallelism();
        }
        return config.get(PipelineOptions.PARALLELISM_OVERRIDES).values().stream()
                .mapToInt(Integer::parseInt)
                .max()
                .getAsInt();
    }
}
