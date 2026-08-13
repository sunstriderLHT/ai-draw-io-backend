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
public class AiUserQuotaPO {

    private String userId;
    private Integer freeGranted;
    private Integer purchasedGranted;
    private Integer consumed;
    private Integer reserved;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}