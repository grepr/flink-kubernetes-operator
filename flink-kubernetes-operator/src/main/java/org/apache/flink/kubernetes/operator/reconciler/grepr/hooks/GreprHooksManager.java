package org.apache.flink.kubernetes.operator.reconciler.grepr.hooks;

import org.apache.flink.kubernetes.operator.reconciler.grepr.ArtificiallyInducedSlotProvisioner;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;

/** Factory for creating {@link GreprHook}s. */
@UtilityClass
public class GreprHooksManager {

    private static final Map<GreprHookType, List<GreprHook>> GREPR_HOOKS =
            Map.of(
                    GreprHookType.SESSION_JOB_PRE_SCALE_UP,
                    List.of(
                            new SessionJobWaitForResources(
                                    new ArtificiallyInducedSlotProvisioner())));

    public static List<GreprHook> getExecutableHooks(GreprHookType type) {
        return GREPR_HOOKS.getOrDefault(type, List.of());
    }

    public static GreprHookStatus getStatus(List<GreprHookStatus> greprHooksStatus) {
        boolean failed = false;
        for (GreprHookStatus status : greprHooksStatus) {
            if (status == GreprHookStatus.PENDING) {
                return GreprHookStatus.PENDING;
            }
            if (status == GreprHookStatus.FAILED) {
                failed = true;
            }
        }
        return failed ? GreprHookStatus.FAILED : GreprHookStatus.COMPLETED;
    }
}
