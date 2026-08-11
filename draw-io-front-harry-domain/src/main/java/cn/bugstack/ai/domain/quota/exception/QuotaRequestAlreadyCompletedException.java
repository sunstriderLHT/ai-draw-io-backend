package cn.bugstack.ai.domain.quota.exception;

import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;

public class QuotaRequestAlreadyCompletedException
        extends AppException {

    public QuotaRequestAlreadyCompletedException() {
        super(
                ResponseCode.AI_REQUEST_ALREADY_COMPLETED.getCode(),
                ResponseCode.AI_REQUEST_ALREADY_COMPLETED.getInfo()
        );
    }
}