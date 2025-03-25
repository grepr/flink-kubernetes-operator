package org.apache.flink.kubernetes.operator.hooks;

/** Test hook implementation of {@link FlinkResourceHook}. */
public class TestHook implements FlinkResourceHook {

    @Override
    public String getName() {
        return "NoopTestHook";
    }

    @Override
    public FlinkResourceHookStatus execute(FlinkResourceHookContext context) {
        return FlinkResourceHookStatus.COMPLETED_STATUS;
    }

    @Override
    public FlinkResourceHookType getHookType() {
        return FlinkResourceHookType.SESSION_JOB_PRE_SCALE_UP;
    }
}
