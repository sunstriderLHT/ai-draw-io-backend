package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiQuotaLedgerPO {

    private Long id;
    private String requestId;
    private String userId;
    private String agentId;
    private String endpoint;
    private String entryType;
    private Integer amount;
    private String status;
    private String sourceRef;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}