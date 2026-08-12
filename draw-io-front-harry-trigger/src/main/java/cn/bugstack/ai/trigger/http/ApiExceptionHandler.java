package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.QuotaResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.domain.quota.exception.QuotaExhaustedException;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestAlreadyCompletedException;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestInProgressException;
import org.springframework.web.bind.MissingRequestHeaderException;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(QuotaExhaustedException.class)
    public ResponseEntity<Response<QuotaResponseDTO>> handleQuotaExhausted(QuotaExhaustedException exception) {
        QuotaSnapshotEntity snapshot = exception.getSnapshot();

        QuotaResponseDTO data =
                new QuotaResponseDTO(
                        snapshot.freeGranted(),
                        snapshot.purchasedGranted(),
                        snapshot.consumed(),
                        snapshot.reserved(),
                        snapshot.remaining()
                );

        Response<QuotaResponseDTO> body =
                Response.<QuotaResponseDTO>builder()
                        .code(exception.getCode())
                        .info(exception.getInfo())
                        .data(data)
                        .build();

        return ResponseEntity
                .status(HttpStatus.PAYMENT_REQUIRED)
                .body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Response<Void>> handleIllegalArgument(IllegalArgumentException exception) {
        Response<Void> body = Response.<Void>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(body);
    }

    @ExceptionHandler({
            QuotaRequestInProgressException.class,
            QuotaRequestAlreadyCompletedException.class
    })
    public ResponseEntity<Response<Void>> handleQuotaRequestConflict(AppException exception) {
        Response<Void> body =
                Response.<Void>builder()
                        .code(exception.getCode())
                        .info(exception.getInfo())
                        .build();

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(body);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Response<Void>> handleMissingRequestHeader(MissingRequestHeaderException exception) {
        Response<Void> body =
                Response.<Void>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(body);
    }
}
