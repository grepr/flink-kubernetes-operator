package org.apache.flink.kubernetes.operator.hooks.flink;

import org.apache.flink.api.common.JobID;
import org.apache.flink.kubernetes.operator.api.spec.FlinkSessionJobSpec;
import org.apache.flink.runtime.messages.webmonitor.MultipleJobsDetails;
import org.apache.flink.runtime.rest.handler.legacy.messages.ClusterOverviewWithVersion;
import org.apache.flink.runtime.rest.messages.job.JobDetailsInfo;

/** Used for interacting with the corresponding flink service by the hooks. */
public interface FlinkCluster {

    JobID submitJob(FlinkSessionJobSpec spec) throws Exception;

    ClusterOverviewWithVersion getClusterOverview();

    MultipleJobsDetails getJobs();

    void cancelJob(JobID jobId);

    JobDetailsInfo getJobDetails(JobID jobId);
}
