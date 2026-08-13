package cn.bugstack.ai.domain.quota.exception;

import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;

public class QuotaRequestInProgressException extends AppException {

    public QuotaRequestInProgressException() {
        super(
                ResponseCode.AI_REQUEST_IN_PROGRESS.getCode(),
                ResponseCode.AI_REQUEST_IN_PROGRESS.getInfo()
        );
    }
}