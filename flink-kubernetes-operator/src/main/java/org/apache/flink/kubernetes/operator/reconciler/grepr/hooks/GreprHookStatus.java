package org.apache.flink.kubernetes.operator.reconciler.grepr.hooks;

/** Status of a {@link GreprHook}. */
public enum GreprHookStatus {
    NOT_APPLICABLE,
    PENDING,
    COMPLETED,
    FAILED;
}
