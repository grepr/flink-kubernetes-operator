package org.apache.flink.kubernetes.operator.reconciler.sessionjob;

import org.apache.flink.kubernetes.operator.hooks.FlinkResourceHook;
import org.apache.flink.kubernetes.operator.hooks.FlinkResourceHookStatus;
import org.apache.flink.kubernetes.operator.hooks.FlinkResourceHookType;
import org.apache.flink.kubernetes.operator.hooks.FlinkResourceHooksManager;
import org.apache.flink.kubernetes.operator.utils.EventCollector;
import org.apache.flink.kubernetes.operator.utils.EventRecorder;

import lombok.Getter;
import lombok.Setter;

import java.util.Collections;

/** Test implementation of FlinkResourceHooksManager that allows controlling hook behavior. */
class TestFlinkResourceHooksManager extends FlinkResourceHooksManager {
    @Setter private FlinkResourceHookStatus hookStatus;
    @Getter private int executionCount = 0;

    private final EventRecorder recorder;

    public TestFlinkResourceHooksManager(EventRecorder eventRecorder) {
        super(Collections.emptyList(), new EventRecorder(new EventCollector()));
        this.recorder = eventRecorder;
    }

    @Override
    public FlinkResourceHookStatus executeAllHooks(
            FlinkResourceHookType hookType, FlinkResourceHook.FlinkResourceHookContext context) {
        executionCount++;
        switch (hookStatus.getStatus()) {
            case PENDING:
                recorder.triggerEvent(
                        context.getFlinkSessionJob(),
                        EventRecorder.Type.Normal,
                        EventRecorder.Reason.FlinkResourceHookFinished,
                        EventRecorder.Component.Job,
                        "Test hook pending",
                        context.getKubernetesClient());
                break;
            case COMPLETED:
                recorder.triggerEvent(
                        context.getFlinkSessionJob(),
                        EventRecorder.Type.Normal,
                        EventRecorder.Reason.FlinkResourceHookFinished,
                        EventRecorder.Component.Job,
                        "Test hook completed",
                        context.getKubernetesClient());
                break;
            case FAILED:
                recorder.triggerEvent(
                        context.getFlinkSessionJob(),
                        EventRecorder.Type.Warning,
                        EventRecorder.Reason.FlinkResourceHookFailed,
                        EventRecorder.Component.Job,
                        "Test hook failed",
                        context.getKubernetesClient());
                break;
            default:
                break;
        }
        return hookStatus;
    }
}
