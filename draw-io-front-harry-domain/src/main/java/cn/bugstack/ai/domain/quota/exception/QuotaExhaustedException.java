package cn.bugstack.ai.domain.quota.exception;

import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshot;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;

public class QuotaExhaustedException extends AppException {

    private final QuotaSnapshot snapshot;
    public QuotaExhaustedException(QuotaSnapshot snapshot) {
        super(ResponseCode.AI_QUOTA_EXHAUSTED.getCode(), ResponseCode.AI_QUOTA_EXHAUSTED.getInfo());
        this.snapshot = snapshot;
    }

    public QuotaSnapshot getSnapshot() {
        return snapshot;
    }
}
