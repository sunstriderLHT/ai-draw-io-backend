package cn.bugstack.ai.domain.quota.exception;

import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;

public class QuotaExhaustedException extends AppException {

    private final QuotaSnapshotEntity snapshot;
    public QuotaExhaustedException(QuotaSnapshotEntity snapshot) {
        super(ResponseCode.AI_QUOTA_EXHAUSTED.getCode(), ResponseCode.AI_QUOTA_EXHAUSTED.getInfo());
        this.snapshot = snapshot;
    }

    public QuotaSnapshotEntity getSnapshot() {
        return snapshot;
    }
}
