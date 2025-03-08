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

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.kubernetes.operator.api.spec.FlinkSessionJobSpec;
import org.apache.flink.kubernetes.operator.config.FlinkConfigManager;
import org.apache.flink.kubernetes.operator.service.FlinkService;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Manages the resources required for a Grepr job. */
public class GreprResourceManager {

    private static final Logger LOG = LoggerFactory.getLogger(GreprResourceManager.class);
    public static final String FLINK_NAMESPACE = "flink";

    private final FlinkConfigManager flinkConfigManager;
    private final ArtificiallyInducedSlotProvisioner provisioner;

    public GreprResourceManager(FlinkConfigManager flinkConfigManager) {
        this.flinkConfigManager = flinkConfigManager;
        this.provisioner = new ArtificiallyInducedSlotProvisioner();
    }

    public void provisionResourcesIfRequired(
            Configuration currentConfig,
            Configuration deployConfig,
            KubernetesClient kubernetesClient,
            String jobName,
            FlinkSessionJobSpec lastReconciledSpec,
            FlinkService flinkService)
            throws Exception {
        var oldParallelism = getMaxParallelism(currentConfig, lastReconciledSpec);
        var newParallelism = getMaxParallelism(deployConfig, lastReconciledSpec);

        if (oldParallelism <= newParallelism) {
            LOG.info(
                    "Provisioning new resources. Current parallelism: {} < new parallelism: {}",
                    oldParallelism,
                    newParallelism);
            var diff = newParallelism - oldParallelism;
            LOG.info("Provisioning {} new slots", diff);
            provisioner.provisionSlots(2, flinkService, deployConfig);
        } else {
            LOG.info(
                    "No need to provision new resources. Current parallelism: {} <= new parallelism: {}",
                    oldParallelism,
                    newParallelism);
        }
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
