/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.reconciler.sessionjob;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.autoscaler.NoopJobAutoscaler;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.operator.OperatorTestBase;
import org.apache.flink.kubernetes.operator.TestFlinkResourceHooksManager;
import org.apache.flink.kubernetes.operator.TestUtils;
import org.apache.flink.kubernetes.operator.api.FlinkSessionJob;
import org.apache.flink.kubernetes.operator.api.spec.FlinkSessionJobSpec;
import org.apache.flink.kubernetes.operator.api.spec.JobState;
import org.apache.flink.kubernetes.operator.api.spec.UpgradeMode;
import org.apache.flink.kubernetes.operator.api.status.FlinkSessionJobStatus;
import org.apache.flink.kubernetes.operator.api.status.JobStatus;
import org.apache.flink.kubernetes.operator.api.status.ReconciliationState;
import org.apache.flink.kubernetes.operator.api.status.SnapshotTriggerType;
import org.apache.flink.kubernetes.operator.config.FlinkConfigManager;
import org.apache.flink.kubernetes.operator.config.KubernetesOperatorConfigOptions;
import org.apache.flink.kubernetes.operator.hooks.FlinkResourceHookStatus;
import org.apache.flink.kubernetes.operator.reconciler.ReconciliationUtils;
import org.apache.flink.kubernetes.operator.reconciler.TestReconcilerAdapter;
import org.apache.flink.kubernetes.operator.utils.SnapshotUtils;
import org.apache.flink.runtime.client.JobStatusMessage;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import lombok.Getter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static org.apache.flink.api.common.JobStatus.CANCELED;
import static org.apache.flink.api.common.JobStatus.CANCELLING;
import static org.apache.flink.api.common.JobStatus.CREATED;
import static org.apache.flink.api.common.JobStatus.FAILED;
import static org.apache.flink.api.common.JobStatus.FAILING;
import static org.apache.flink.api.common.JobStatus.FINISHED;
import static org.apache.flink.api.common.JobStatus.INITIALIZING;
import static org.apache.flink.api.common.JobStatus.RECONCILING;
import static org.apache.flink.api.common.JobStatus.RESTARTING;
import static org.apache.flink.api.common.JobStatus.RUNNING;
import static org.apache.flink.api.common.JobStatus.SUSPENDED;
import static org.apache.flink.kubernetes.operator.api.utils.FlinkResourceUtils.getCheckpointInfo;
import static org.apache.flink.kubernetes.operator.api.utils.FlinkResourceUtils.getJobSpec;
import static org.apache.flink.kubernetes.operator.api.utils.FlinkResourceUtils.getJobStatus;
import static org.apache.flink.kubernetes.operator.api.utils.FlinkResourceUtils.getReconciledJobSpec;
import static org.apache.flink.kubernetes.operator.config.KubernetesOperatorConfigOptions.OPERATOR_JOB_RESTART_FAILED;
import static org.apache.flink.kubernetes.operator.hooks.FlinkResourceHookUtils.FLINK_RESOURCES_HOOKS_ACTIVE_KEY;
import static org.apache.flink.kubernetes.operator.hooks.FlinkResourceHookUtils.FLINK_RESOURCES_HOOKS_RECONCILIATION_INTERVAL_KEY;
import static org.apache.flink.kubernetes.operator.reconciler.SnapshotType.CHECKPOINT;
import static org.apache.flink.kubernetes.operator.reconciler.SnapshotType.SAVEPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link SessionJobReconciler}. */
@EnableKubernetesMockClient(crud = true)
public class SessionJobReconcilerTest extends OperatorTestBase {

    @Getter private KubernetesClient kubernetesClient;
    private TestFlinkResourceHooksManager hooksManager;

    private TestReconcilerAdapter<FlinkSessionJob, FlinkSessionJobSpec, FlinkSessionJobStatus>
            reconciler;

    @Override
    public void setup() {
        var configuration = new Configuration();
        configuration.set(OPERATOR_JOB_RESTART_FAILED, true);
        configManager = new FlinkConfigManager(configuration);
        hooksManager = new TestFlinkResourceHooksManager(eventRecorder);
        reconciler =
                new TestReconcilerAdapter<>(
                        this,
                        new SessionJobReconciler(
                                eventRecorder,
                                statusRecorder,
                                new NoopJobAutoscaler<>(),
                                hooksManager));
    }

    @Test
    public void testSubmitAndCleanUpWithSavepoint() throws Exception {
        var conf = configManager.getDefaultConfig();
        conf.set(KubernetesOperatorConfigOptions.SAVEPOINT_ON_DELETION, true);
        configManager.updateDefaultConfig(conf);

        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        // session ready
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        // clean up
        reconciler.cleanup(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(
                "savepoint_0",
                sessionJob
                        .getStatus()
                        .getJobStatus()
                        .getSavepointInfo()
                        .getLastSavepoint()
                        .getLocation());
    }

    @Test
    public void testSubmitAndCleanUpWithSavepointOnResource() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();
        sessionJob
                .getSpec()
                .getFlinkConfiguration()
                .put(KubernetesOperatorConfigOptions.SAVEPOINT_ON_DELETION.key(), "true");

        // session ready
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        // clean up
        reconciler.cleanup(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(
                "savepoint_0",
                sessionJob
                        .getStatus()
                        .getJobStatus()
                        .getSavepointInfo()
                        .getLastSavepoint()
                        .getLocation());
    }

    @Test
    public void testSubmitAndCleanUp() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        // session not found
        reconciler.reconcile(sessionJob, TestUtils.createEmptyContext());
        assertEquals(0, flinkService.listJobs().size());

        // session not ready
        reconciler.reconcile(sessionJob, TestUtils.createContextWithNotReadyFlinkDeployment());
        assertEquals(0, flinkService.listJobs().size());

        // session ready
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());
        // clean up
        reconciler.cleanup(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(FINISHED, flinkService.listJobs().get(0).f1.getJobState());
    }

    @Test
    public void testCancelJobRescheduled() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        // session ready
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());
        // clean up
        flinkService.setPortReady(false);
        var deleteControl =
                reconciler.cleanup(
                        sessionJob,
                        TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(10_000, deleteControl.getScheduleDelay().get());
        assertEquals(RUNNING, flinkService.listJobs().get(0).f1.getJobState());

        flinkService.setPortReady(true);
        deleteControl =
                reconciler.cleanup(
                        sessionJob,
                        TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(true, deleteControl.isRemoveFinalizer());
        assertEquals(FINISHED, flinkService.listJobs().get(0).f1.getJobState());
    }

    @Test
    public void testCancelJobNotFound() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        // session ready
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());
        // clean up
        flinkService.setFlinkJobNotFound(true);
        var deleteControl =
                reconciler.cleanup(
                        sessionJob,
                        TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));

        deleteControl =
                reconciler.cleanup(
                        sessionJob,
                        TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(true, deleteControl.isRemoveFinalizer());
    }

    @Test
    public void testCancelJobTerminatedWithoutCancellation() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        // session ready
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());
        // clean up
        flinkService.setFlinkJobTerminatedWithoutCancellation(true);
        var deleteControl =
                reconciler.cleanup(
                        sessionJob,
                        TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));

        deleteControl =
                reconciler.cleanup(
                        sessionJob,
                        TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(true, deleteControl.isRemoveFinalizer());
    }

    @Test
    public void testRestart() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        // session ready
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());
        sessionJob.getSpec().setRestartNonce(2L);
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        assertEquals(FINISHED, flinkService.listJobs().get(0).f1.getJobState());
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());
    }

    @Test
    public void testRestartWhenFailed() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();
        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);

        // session ready
        reconciler.reconcile(sessionJob, readyContext);
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        sessionJob.getStatus().getJobStatus().setState(FAILED.name());
        reconciler.reconcile(sessionJob, readyContext);
        assertEquals(2, flinkService.listJobs().size());
        assertEquals(RUNNING, flinkService.listJobs().get(1).f1.getJobState());
    }

    @Test
    public void testSubmitWithInitialSavepointPath() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        var initSavepointPath = "file:///init-sp";
        sessionJob.getSpec().getJob().setInitialSavepointPath(initSavepointPath);
        reconciler.reconcile(
                sessionJob, TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient));
        verifyAndSetRunningJobsToStatus(
                sessionJob,
                JobState.RUNNING,
                RECONCILING.name(),
                initSavepointPath,
                flinkService.listJobs());
    }

    @Test
    public void testStatelessUpgrade() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);
        reconciler.reconcile(sessionJob, readyContext);
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        var statelessSessionJob = ReconciliationUtils.clone(sessionJob);
        statelessSessionJob.getSpec().getJob().setUpgradeMode(UpgradeMode.STATELESS);
        statelessSessionJob.getSpec().getJob().setParallelism(2);
        // job suspended first
        reconciler.reconcile(statelessSessionJob, readyContext);
        assertEquals(FINISHED, flinkService.listJobs().get(0).f1.getJobState());
        verifyJobState(statelessSessionJob, JobState.SUSPENDED, "FINISHED");

        flinkService.clear();
        reconciler.reconcile(statelessSessionJob, readyContext);
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                statelessSessionJob,
                JobState.RUNNING,
                RECONCILING.name(),
                null,
                flinkService.listJobs());
    }

    @Test
    public void testSavepointUpgrade() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);
        reconciler.reconcile(sessionJob, readyContext);
        // start the job
        assertEquals(1, flinkService.listJobs().size());
        assertTrue(
                sessionJob
                        .getStatus()
                        .getJobStatus()
                        .getSavepointInfo()
                        .getSavepointHistory()
                        .isEmpty());

        // update job spec
        var statefulSessionJob = ReconciliationUtils.clone(sessionJob);
        statefulSessionJob.getSpec().getJob().setUpgradeMode(UpgradeMode.SAVEPOINT);
        statefulSessionJob.getSpec().getJob().setParallelism(3);

        verifyAndSetRunningJobsToStatus(
                statefulSessionJob,
                JobState.RUNNING,
                RECONCILING.name(),
                null,
                flinkService.listJobs());

        reconciler.reconcile(statefulSessionJob, readyContext);

        // job suspended first
        assertEquals(FINISHED, flinkService.listJobs().get(0).f1.getJobState());
        verifyJobState(statefulSessionJob, JobState.SUSPENDED, "FINISHED");
        assertEquals(
                1,
                statefulSessionJob
                        .getStatus()
                        .getJobStatus()
                        .getSavepointInfo()
                        .getSavepointHistory()
                        .size());
        assertEquals(
                SnapshotTriggerType.UPGRADE,
                statefulSessionJob
                        .getStatus()
                        .getJobStatus()
                        .getSavepointInfo()
                        .getLastSavepoint()
                        .getTriggerType());

        flinkService.clear();
        // upgraded
        reconciler.reconcile(statefulSessionJob, readyContext);
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                statefulSessionJob,
                JobState.RUNNING,
                RECONCILING.name(),
                "savepoint_0",
                flinkService.listJobs());
    }

    @Test
    public void testTriggerSavepoint() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();
        assertFalse(SnapshotUtils.savepointInProgress(sessionJob.getStatus().getJobStatus()));

        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);
        reconciler.reconcile(sessionJob, readyContext);
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        assertFalse(SnapshotUtils.savepointInProgress(sessionJob.getStatus().getJobStatus()));

        // trigger savepoint
        var sp1SessionJob = ReconciliationUtils.clone(sessionJob);

        // do not trigger savepoint if nonce is null
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertFalse(SnapshotUtils.savepointInProgress(sp1SessionJob.getStatus().getJobStatus()));

        sp1SessionJob.getSpec().getJob().setSavepointTriggerNonce(2L);
        sp1SessionJob.getStatus().getJobStatus().setState(CREATED.name());
        reconciler.reconcile(sp1SessionJob, readyContext);
        // do not trigger savepoint if job is not running
        assertFalse(SnapshotUtils.savepointInProgress(sp1SessionJob.getStatus().getJobStatus()));

        sp1SessionJob.getStatus().getJobStatus().setState(RUNNING.name());

        reconciler.reconcile(sp1SessionJob, readyContext);
        assertTrue(SnapshotUtils.savepointInProgress(sp1SessionJob.getStatus().getJobStatus()));

        // the last reconcile nonce updated
        assertNull(
                sp1SessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getSavepointTriggerNonce());

        // don't trigger new savepoint when savepoint is in progress
        sp1SessionJob.getSpec().getJob().setSavepointTriggerNonce(3L);
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertEquals(
                "savepoint_trigger_0",
                sp1SessionJob.getStatus().getJobStatus().getSavepointInfo().getTriggerId());

        // don't trigger upgrade when savepoint is in progress
        assertEquals(
                1,
                sp1SessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getParallelism());
        sp1SessionJob.getSpec().getJob().setParallelism(100);
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertEquals(
                "savepoint_trigger_0",
                sp1SessionJob.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertEquals(
                SnapshotTriggerType.MANUAL,
                sp1SessionJob.getStatus().getJobStatus().getSavepointInfo().getTriggerType());

        // parallelism not changed
        assertEquals(
                1,
                sp1SessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getParallelism());

        sp1SessionJob.getStatus().getJobStatus().getSavepointInfo().resetTrigger();
        ReconciliationUtils.updateLastReconciledSnapshotTriggerNonce(
                sp1SessionJob.getStatus().getJobStatus().getSavepointInfo(),
                sp1SessionJob,
                SAVEPOINT);

        // running -> suspended
        reconciler.reconcile(sp1SessionJob, readyContext);
        // suspended -> running
        reconciler.reconcile(sp1SessionJob, readyContext);
        // parallelism changed
        assertEquals(
                100,
                sp1SessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getParallelism());
        verifyAndSetRunningJobsToStatus(
                sp1SessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        sp1SessionJob.getStatus().getJobStatus().getSavepointInfo().resetTrigger();
        ReconciliationUtils.updateLastReconciledSnapshotTriggerNonce(
                sp1SessionJob.getStatus().getJobStatus().getSavepointInfo(),
                sp1SessionJob,
                SAVEPOINT);

        // trigger when new nonce is defined
        sp1SessionJob.getSpec().getJob().setSavepointTriggerNonce(4L);
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertEquals(
                "savepoint_trigger_1",
                sp1SessionJob.getStatus().getJobStatus().getSavepointInfo().getTriggerId());

        sp1SessionJob.getStatus().getJobStatus().getSavepointInfo().resetTrigger();
        ReconciliationUtils.updateLastReconciledSnapshotTriggerNonce(
                sp1SessionJob.getStatus().getJobStatus().getSavepointInfo(),
                sp1SessionJob,
                SAVEPOINT);

        // don't trigger when nonce is cleared
        sp1SessionJob.getSpec().getJob().setSavepointTriggerNonce(null);
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertFalse(SnapshotUtils.savepointInProgress(sp1SessionJob.getStatus().getJobStatus()));
    }

    @Test
    public void testTriggerCheckpoint() throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();
        assertFalse(SnapshotUtils.checkpointInProgress(getJobStatus(sessionJob)));

        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);
        reconciler.reconcile(sessionJob, readyContext);
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        assertFalse(SnapshotUtils.checkpointInProgress(getJobStatus(sessionJob)));

        // trigger checkpoint
        var sp1SessionJob = ReconciliationUtils.clone(sessionJob);

        // do not trigger checkpoint if nonce is null
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertFalse(SnapshotUtils.checkpointInProgress(getJobStatus(sp1SessionJob)));

        getJobSpec(sp1SessionJob).setCheckpointTriggerNonce(2L);
        getJobStatus(sp1SessionJob).setState(CREATED.name());
        reconciler.reconcile(sp1SessionJob, readyContext);
        // do not trigger checkpoint if job is not running
        assertFalse(SnapshotUtils.checkpointInProgress(getJobStatus(sp1SessionJob)));

        getJobStatus(sp1SessionJob).setState(RUNNING.name());

        reconciler.reconcile(sp1SessionJob, readyContext);
        assertTrue(SnapshotUtils.checkpointInProgress(getJobStatus(sp1SessionJob)));

        // the last reconcile nonce updated
        assertNull(getReconciledJobSpec(sp1SessionJob).getCheckpointTriggerNonce());

        // don't trigger new checkpoint when checkpoint is in progress
        getJobSpec(sp1SessionJob).setCheckpointTriggerNonce(3L);
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertEquals("checkpoint_trigger_0", getCheckpointInfo(sp1SessionJob).getTriggerId());

        // trigger when new nonce is defined
        getJobSpec(sp1SessionJob).setCheckpointTriggerNonce(4L);
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertEquals("checkpoint_trigger_0", getCheckpointInfo(sp1SessionJob).getTriggerId());

        getCheckpointInfo(sp1SessionJob).resetTrigger();
        ReconciliationUtils.updateLastReconciledSnapshotTriggerNonce(
                getCheckpointInfo(sp1SessionJob), sp1SessionJob, CHECKPOINT);

        // don't trigger when nonce is cleared
        getJobSpec(sp1SessionJob).setCheckpointTriggerNonce(null);
        reconciler.reconcile(sp1SessionJob, readyContext);
        assertFalse(SnapshotUtils.checkpointInProgress(getJobStatus(sp1SessionJob)));
    }

    private static Stream<Arguments> cancelStatelessSessionJobParams() {
        return Stream.of(
                Arguments.of(INITIALIZING, true),
                Arguments.of(CREATED, true),
                Arguments.of(RUNNING, true),
                Arguments.of(FAILING, true),
                Arguments.of(FAILED, false),
                Arguments.of(CANCELLING, true),
                Arguments.of(CANCELED, false),
                Arguments.of(FINISHED, false),
                Arguments.of(RESTARTING, true),
                Arguments.of(SUSPENDED, true),
                Arguments.of(RECONCILING, true));
    }

    @Test
    public void testCancelStatelessSessionJobParams() {
        assertEquals(
                org.apache.flink.api.common.JobStatus.values().length,
                cancelStatelessSessionJobParams().count());
    }

    @ParameterizedTest
    @MethodSource("cancelStatelessSessionJobParams")
    public void testCancelStatelessSessionJob(
            org.apache.flink.api.common.JobStatus fromJobStatus, boolean shouldCallCancel)
            throws Exception {
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);

        reconciler.reconcile(sessionJob, readyContext);
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        var job = flinkService.listJobs().get(0);
        var jobStatusMessage = job.f1;
        var jobConfig = job.f2;

        sessionJob.getSpec().getJob().setUpgradeMode(UpgradeMode.STATELESS);

        // Set JobStatusMessage which may be overwritten by cancel
        job.f1 =
                new JobStatusMessage(
                        jobStatusMessage.getJobId(),
                        jobStatusMessage.getJobName(),
                        fromJobStatus,
                        jobStatusMessage.getStartTime());
        // Set state which must be overwritten by cancelSessionJob
        sessionJob.getStatus().getJobStatus().setState(fromJobStatus.name());

        flinkService.cancelSessionJob(sessionJob, UpgradeMode.STATELESS, jobConfig);

        if (!shouldCallCancel) {
            assertEquals(0, flinkService.getCancelJobCallCount());
            assertEquals(fromJobStatus, job.f1.getJobState());
        } else {
            assertEquals(1, flinkService.getCancelJobCallCount());
            assertEquals(FINISHED, job.f1.getJobState());
        }
        assertEquals(FINISHED.name(), sessionJob.getStatus().getJobStatus().getState());
    }

    private static Stream<Arguments> cancelSavepointSessionJobParams() {
        return Stream.of(
                Arguments.of(INITIALIZING, true, false),
                Arguments.of(CREATED, true, false),
                Arguments.of(RUNNING, false, true),
                Arguments.of(FAILING, true, false),
                Arguments.of(FAILED, false, false),
                Arguments.of(CANCELLING, true, false),
                Arguments.of(CANCELED, false, false),
                Arguments.of(FINISHED, false, false),
                Arguments.of(RESTARTING, true, false),
                Arguments.of(SUSPENDED, true, false),
                Arguments.of(RECONCILING, true, false));
    }

    @Test
    public void testCancelSavepointSessionJobParams() {
        assertEquals(
                org.apache.flink.api.common.JobStatus.values().length,
                cancelSavepointSessionJobParams().count());
    }

    @ParameterizedTest
    @MethodSource("cancelSavepointSessionJobParams")
    public void testCancelSavepointSessionJob(
            org.apache.flink.api.common.JobStatus fromJobStatus,
            boolean shouldThrowException,
            boolean shouldCallCancel)
            throws Exception {
        assertTrue(
                !shouldThrowException || !shouldCallCancel,
                "Expecting an exception and cancel to be called is and oxymoron");

        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();

        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);

        reconciler.reconcile(sessionJob, readyContext);
        assertEquals(1, flinkService.listJobs().size());
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        var job = flinkService.listJobs().get(0);
        var jobStatusMessage = job.f1;
        var jobConfig = job.f2;

        sessionJob.getSpec().getJob().setUpgradeMode(UpgradeMode.SAVEPOINT);

        // Set JobStatusMessage which may be overwritten by cancel
        job.f1 =
                new JobStatusMessage(
                        jobStatusMessage.getJobId(),
                        jobStatusMessage.getJobName(),
                        fromJobStatus,
                        jobStatusMessage.getStartTime());
        // Set state which must be overwritten by cancelSessionJob
        sessionJob.getStatus().getJobStatus().setState(fromJobStatus.name());

        if (!shouldThrowException) {
            flinkService.cancelSessionJob(sessionJob, UpgradeMode.SAVEPOINT, jobConfig);
        } else {
            var e =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    flinkService.cancelSessionJob(
                                            sessionJob, UpgradeMode.SAVEPOINT, jobConfig));
            Assertions.assertTrue(e.getMessage().contains("Unexpected non-terminal status"));
        }

        if (!shouldCallCancel) {
            assertEquals(0, flinkService.getCancelJobCallCount());
            assertNull(job.f0);
            assertEquals(fromJobStatus, job.f1.getJobState());
        } else {
            assertEquals(1, flinkService.getCancelJobCallCount());
            assertEquals("savepoint_0", job.f0);
            assertEquals(FINISHED, job.f1.getJobState());
        }
        if (!shouldThrowException) {
            assertEquals(FINISHED.name(), sessionJob.getStatus().getJobStatus().getState());
        }
    }

    private Tuple3<String, JobStatusMessage, Configuration> verifyAndReturnTheSubmittedJob(
            FlinkSessionJob sessionJob,
            List<Tuple3<String, JobStatusMessage, Configuration>> jobs) {
        var jobID = JobID.fromHexString(sessionJob.getStatus().getJobStatus().getJobId());
        var submittedJobInfo =
                jobs.stream().filter(t -> t.f1.getJobId().equals(jobID)).findAny().get();
        Assertions.assertNotNull(submittedJobInfo);
        return submittedJobInfo;
    }

    private void verifyAndSetRunningJobsToStatus(
            FlinkSessionJob sessionJob,
            JobState expectedState,
            String jobStatusObserved,
            @Nullable String expectedSavepointPath,
            List<Tuple3<String, JobStatusMessage, Configuration>> jobs) {

        var submittedJobInfo = verifyAndReturnTheSubmittedJob(sessionJob, jobs);
        assertEquals(expectedSavepointPath, submittedJobInfo.f0);

        verifyJobState(sessionJob, expectedState, jobStatusObserved);
        JobStatus jobStatus = sessionJob.getStatus().getJobStatus();
        jobStatus.setJobName(submittedJobInfo.f1.getJobName());
        jobStatus.setState("RUNNING");
    }

    private void verifyJobState(
            FlinkSessionJob sessionJob, JobState expectedState, String jobStatusObserved) {
        assertEquals(
                expectedState,
                sessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getState());

        assertEquals(jobStatusObserved, sessionJob.getStatus().getJobStatus().getState());
    }

    @Test
    public void testJobUpgradeIgnorePendingSavepoint() throws Exception {
        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();
        reconciler.reconcile(sessionJob, readyContext);
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        FlinkSessionJob spSessionJob = ReconciliationUtils.clone(sessionJob);
        spSessionJob
                .getSpec()
                .getJob()
                .setSavepointTriggerNonce(ThreadLocalRandom.current().nextLong());
        reconciler.reconcile(spSessionJob, readyContext);
        assertEquals(
                "savepoint_trigger_0",
                spSessionJob.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertEquals(JobState.RUNNING.name(), spSessionJob.getStatus().getJobStatus().getState());

        configManager.updateDefaultConfig(
                Configuration.fromMap(
                        Map.of(
                                KubernetesOperatorConfigOptions.JOB_UPGRADE_IGNORE_PENDING_SAVEPOINT
                                        .key(),
                                "true")));
        // Force upgrade when savepoint is in progress.
        spSessionJob.getSpec().getJob().setParallelism(100);
        reconciler.reconcile(spSessionJob, readyContext);
        assertEquals(
                "savepoint_trigger_0",
                spSessionJob.getStatus().getJobStatus().getSavepointInfo().getTriggerId());
        assertEquals("FINISHED", spSessionJob.getStatus().getJobStatus().getState());
    }

    @Test
    public void testJobIdGeneration() throws Exception {
        var sessionJob = TestUtils.buildSessionJob();
        sessionJob.getMetadata().setGeneration(10L);
        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);

        reconciler.reconcile(sessionJob, readyContext);
        Assertions.assertEquals(
                ReconciliationState.DEPLOYED,
                sessionJob.getStatus().getReconciliationStatus().getState());
        var jobID = sessionJob.getStatus().getJobStatus().getJobId();
        Assertions.assertEquals(
                RECONCILING.name(), sessionJob.getStatus().getJobStatus().getState());
        Assertions.assertEquals(jobID, flinkService.listJobs().get(0).f1.getJobId().toString());

        flinkService.setSessionJobSubmittedCallback(
                () -> {
                    throw new RuntimeException("Failed after submitted job");
                });
        sessionJob.getSpec().getJob().setParallelism(10);
        // upgrade
        Assertions.assertThrows(
                RuntimeException.class,
                () -> {
                    // suspend
                    reconciler.reconcile(sessionJob, readyContext);
                    // upgrade
                    reconciler.reconcile(sessionJob, readyContext);
                });

        Assertions.assertEquals(
                ReconciliationState.UPGRADING,
                sessionJob.getStatus().getReconciliationStatus().getState());
        // New jobID recorded despite failure
        Assertions.assertNotEquals(jobID, sessionJob.getStatus().getJobStatus().getJobId());
    }

    @Test
    public void testFlinkResourceHooksExecution() throws Exception {
        enableResourceHooks();

        // First, create a job for the first time
        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();
        reconciler.reconcile(sessionJob, readyContext);
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());

        assertEquals(0, hooksManager.getExecutionCount());

        assertEquals(
                1,
                sessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getParallelism());

        // Update job spec to trigger a new reconciliation
        sessionJob.getSpec().getJob().setParallelism(2);

        // Test hooks returning PENDING status should block reconciliation
        hooksManager.setHookStatus(
                new FlinkResourceHookStatus(
                        Duration.ofSeconds(30), FlinkResourceHookStatus.Status.PENDING));
        reconciler.reconcile(sessionJob, readyContext);

        // Verify job upgrade was not performed due to pending hook and that the job is still
        // running
        assertEquals(1, hooksManager.getExecutionCount());
        assertEquals(1, flinkService.listJobs().size());
        var reconciledConfig =
                sessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getFlinkConfiguration();
        assertEquals("true", reconciledConfig.get(FLINK_RESOURCES_HOOKS_ACTIVE_KEY));
        assertEquals(
                "PT30S", reconciledConfig.get(FLINK_RESOURCES_HOOKS_RECONCILIATION_INTERVAL_KEY));

        // Test hooks returning COMPLETED status should allow reconciliation to proceed
        hooksManager.setHookStatus(
                new FlinkResourceHookStatus(null, FlinkResourceHookStatus.Status.COMPLETED));
        // This should remove the hook state and cancel the job
        reconciler.reconcile(sessionJob, readyContext);
        assertEquals(2, hooksManager.getExecutionCount());
        reconciledConfig =
                sessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getFlinkConfiguration();
        assertFalse(reconciledConfig.containsKey(FLINK_RESOURCES_HOOKS_ACTIVE_KEY));
        assertFalse(
                reconciledConfig.containsKey(FLINK_RESOURCES_HOOKS_RECONCILIATION_INTERVAL_KEY));
        // Check that job was suspended as part of upgrade
        assertEquals(FINISHED, flinkService.listJobs().get(0).f1.getJobState());
        verifyJobState(sessionJob, JobState.SUSPENDED, "FINISHED");

        flinkService.clear();

        // This should deploy the new job
        reconciler.reconcile(sessionJob, readyContext);

        // Verify job upgrade was performed and that the job is still running
        assertEquals(2, hooksManager.getExecutionCount());
        assertEquals(1, flinkService.listJobs().size());

        // Verify the parallelism was updated
        assertEquals(
                2,
                sessionJob
                        .getStatus()
                        .getReconciliationStatus()
                        .deserializeLastReconciledSpec()
                        .getJob()
                        .getParallelism());
    }

    @Test
    public void testFlinkResourceHooksFailure() throws Exception {
        enableResourceHooks();

        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();
        reconciler.reconcile(sessionJob, readyContext);
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());
        assertEquals(0, hooksManager.getExecutionCount());

        // Update job spec to trigger a new reconciliation
        sessionJob.getSpec().getJob().setParallelism(2);

        // Set hooks to return FAILED status
        hooksManager.setHookStatus(
                new FlinkResourceHookStatus(null, FlinkResourceHookStatus.Status.FAILED));
        reconciler.reconcile(sessionJob, readyContext);

        // Verify reconciliation proceeds normally
        assertEquals(1, hooksManager.getExecutionCount());
        assertEquals(1, flinkService.listJobs().size());
        assertTrue(
                eventCollector.events.stream()
                        .anyMatch(
                                e ->
                                        e.getType().equals("Warning")
                                                && e.getReason()
                                                        .contains("FlinkResourceHookFailed")));
    }

    @Test
    public void testFlinkResourceHooksNotApplicable() throws Exception {
        enableResourceHooks();

        var readyContext = TestUtils.createContextWithReadyFlinkDeployment(kubernetesClient);
        FlinkSessionJob sessionJob = TestUtils.buildSessionJob();
        reconciler.reconcile(sessionJob, readyContext);
        verifyAndSetRunningJobsToStatus(
                sessionJob, JobState.RUNNING, RECONCILING.name(), null, flinkService.listJobs());
        assertEquals(0, hooksManager.getExecutionCount());

        // Update job spec to trigger a new reconciliation
        sessionJob.getSpec().getJob().setParallelism(2);

        // Set hooks to return NOT_APPLICABLE status
        hooksManager.setHookStatus(
                new FlinkResourceHookStatus(null, FlinkResourceHookStatus.Status.NOT_APPLICABLE));
        reconciler.reconcile(sessionJob, readyContext);

        // Verify hooks were executed but reconciliation proceeded normally
        assertEquals(1, hooksManager.getExecutionCount());
        assertEquals(1, flinkService.listJobs().size());
    }

    private void enableResourceHooks() {
        var conf = configManager.getDefaultConfig();
        conf.set(KubernetesOperatorConfigOptions.FLINK_RESOURCE_HOOKS_ENABLED, true);
        configManager.updateDefaultConfig(conf);
    }
}
