package org.apache.flink.kubernetes.operator.hooks;

import org.apache.flink.kubernetes.operator.hooks.FlinkResourceHook.FlinkResourceHookContext;
import org.apache.flink.kubernetes.operator.hooks.FlinkResourceHookStatus.Status;
import org.apache.flink.kubernetes.operator.utils.EventRecorder;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.kubernetes.operator.hooks.FlinkResourceHookStatus.COMPLETED_STATUS;
import static org.apache.flink.kubernetes.operator.hooks.FlinkResourceHookStatus.NOT_APPLICABLE;

/** Manages flink resource hooks defined as plugins. */
public class FlinkResourceHooksManager {

    private final Map<String, Set<FlinkResourceHook>> hooks;
    private final EventRecorder eventRecorder;

    public FlinkResourceHooksManager(
            Collection<FlinkResourceHook> hooks, EventRecorder eventRecorder) {
        this.hooks = new HashMap<>();
        hooks.forEach(
                hook ->
                        this.hooks
                                .computeIfAbsent(hook.getHookType().name(), k -> new HashSet<>())
                                .add(hook));
        this.eventRecorder = eventRecorder;
    }

    public Set<FlinkResourceHook> getHooks(FlinkResourceHookType hookType) {
        return hooks.get(hookType.name());
    }

    /**
     * Executes all hooks registered for the specified hook type against the provided context.
     *
     * <p>This method iterates through the applicable hooks, executes each one, and combines their
     * statuses to determine the overall execution result. It emits events for each hook execution
     * based on its status.
     *
     * <p>The combined status will be:
     *
     * <ul>
     *   <li>NOT_APPLICABLE - if no hooks are found or none are applicable
     *   <li>PENDING - if any hook returns PENDING (using the maximum reconcile interval)
     *   <li>FAILED - if any hook fails and none are pending
     *   <li>COMPLETED - if all applicable hooks complete successfully
     * </ul>
     *
     * @param hookType the type of hook to execute
     * @param context the context containing resources and configuration for hook execution
     * @return a status representing the combined result of all hook executions
     */
    public FlinkResourceHookStatus executeAllHooks(
            FlinkResourceHookType hookType, FlinkResourceHookContext context) {
        var hooks = getHooks(hookType);
        if (hooks == null || hooks.isEmpty()) {
            return COMPLETED_STATUS;
        }

        Duration maxReconcileInterval = Duration.ZERO;
        boolean isPending = false;
        boolean hasFailed = false;
        boolean anyApplicable = false;

        for (FlinkResourceHook hook : hooks) {
            var status = hook.execute(context);
            emitHookStatusEvent(hook, status, context);
            if (status != NOT_APPLICABLE) {
                anyApplicable = true;

                if (status.getStatus() == Status.PENDING) {
                    maxReconcileInterval =
                            maxReconcileInterval.compareTo(status.getReconcileInterval()) > 0
                                    ? maxReconcileInterval
                                    : status.getReconcileInterval();
                    isPending = true;
                } else if (status.getStatus() == Status.FAILED) {
                    hasFailed = true;
                }
            }
        }

        if (!anyApplicable) {
            return NOT_APPLICABLE;
        }

        return isPending
                ? new FlinkResourceHookStatus(maxReconcileInterval, Status.PENDING)
                : hasFailed ? new FlinkResourceHookStatus(null, Status.FAILED) : COMPLETED_STATUS;
    }

    private void emitHookStatusEvent(
            FlinkResourceHook hook, FlinkResourceHookStatus status, FlinkResourceHookContext ctx) {
        switch (status.getStatus()) {
            case COMPLETED:
                eventRecorder.triggerEvent(
                        ctx.getFlinkSessionJob(),
                        EventRecorder.Type.Normal,
                        EventRecorder.Reason.FlinkSessionJobHookFinished,
                        EventRecorder.Component.Job,
                        String.format("Grepr hook with name %s finished", hook.getName()),
                        ctx.getKubernetesClient());
                break;
            case PENDING:
                eventRecorder.triggerEvent(
                        ctx.getFlinkSessionJob(),
                        EventRecorder.Type.Normal,
                        EventRecorder.Reason.FlinkSessionJobHookPending,
                        EventRecorder.Component.Job,
                        String.format("Grepr hook with name %s is still running", hook.getName()),
                        ctx.getKubernetesClient());
                break;
            case FAILED:
                eventRecorder.triggerEvent(
                        ctx.getFlinkSessionJob(),
                        EventRecorder.Type.Warning,
                        EventRecorder.Reason.FlinkSessionJobHookFailed,
                        EventRecorder.Component.Job,
                        String.format("Grepr hook with name %s failed", hook.getName()),
                        ctx.getKubernetesClient());
                break;
            default:
                break;
        }
    }
}
