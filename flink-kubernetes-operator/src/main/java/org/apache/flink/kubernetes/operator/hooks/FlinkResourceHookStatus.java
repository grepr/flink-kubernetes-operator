package org.apache.flink.kubernetes.operator.hooks;

import lombok.Data;

import java.time.Duration;

/** Status of a {@link FlinkResourceHook}. */
@Data
public class FlinkResourceHookStatus {

    public static final FlinkResourceHookStatus NOT_APPLICABLE =
            new FlinkResourceHookStatus(null, Status.NOT_APPLICABLE);
    public static final FlinkResourceHookStatus COMPLETED_STATUS =
            new FlinkResourceHookStatus(null, Status.COMPLETED);

    /** The interval after which resource reconciliation happens in this state. */
    private final Duration reconcileInterval;

    /** Status of the hook. */
    private final Status status;

    /** Status of the hook. */
    public enum Status {
        NOT_APPLICABLE,
        PENDING,
        COMPLETED,
        FAILED
    }
}
