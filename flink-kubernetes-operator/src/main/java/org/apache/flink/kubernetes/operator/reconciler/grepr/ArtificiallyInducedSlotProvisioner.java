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

package org.apache.flink.kubernetes.operator.reconciler.grepr;

import org.apache.flink.api.common.JobID;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.operator.api.spec.FlinkSessionJobSpec;
import org.apache.flink.kubernetes.operator.api.spec.JobSpec;
import org.apache.flink.kubernetes.operator.service.FlinkService;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flink.kubernetes.operator.config.FlinkConfigBuilder.FLINK_VERSION;

/** Provisions task managers for a Flink cluster. */
public class ArtificiallyInducedSlotProvisioner {

    private static final Logger LOG =
            LoggerFactory.getLogger(ArtificiallyInducedSlotProvisioner.class);

    public void provisionSlots(
            int withParallelism,
            FlinkService flinkService,
            Configuration deployConfig,
            String jobName)
            throws Exception {
        LOG.info("Provisioning task managers with parallelism {}", withParallelism);
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
        LOG.info("Configuration: {}", conf);
        var objectMeta = new ObjectMeta();
        objectMeta.setNamespace(deployConfig.get(KubernetesConfigOptions.NAMESPACE));
        var jobId =
                flinkService.submitJobToSessionCluster(
                        objectMeta,
                        FlinkSessionJobSpec.builder()
                                .deploymentName(
                                        deployConfig.get(KubernetesConfigOptions.CLUSTER_ID))
                                .job(
                                        JobSpec.builder()
                                                .entryClass(
                                                        "io.grepr.query.apps.pipeline.taskmanagerprovisioner.TaskManagerProvisionerApp")
                                                .args(
                                                        new String[] {
                                                            jobName,
                                                            Integer.toString(withParallelism)
                                                        })
                                                .build())
                                .build(),
                        JobID.generate(),
                        conf,
                        null);
        LOG.info("Submitted job {} to provision task managers", jobId);
    }
}
