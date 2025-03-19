package org.apache.flink.kubernetes.operator.hooks;

/** Type of the {@link FlinkResourceHook}. */
public enum FlinkResourceHookType {

    /** Hook gets executed before a session job is stopped for scale-up. */
    SESSION_JOB_PRE_SCALE_UP
}
