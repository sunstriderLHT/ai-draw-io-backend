package cn.bugstack.ai.domain.quota.service;

import cn.bugstack.ai.domain.quota.model.entity.QuotaReservationEntity;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshot;

import java.time.Instant;

public interface IAiQuotaService {

    QuotaReservationEntity reserve(
            String userId,
            String requestId,
            String agentId,
            String endpoint
    );

    QuotaSnapshot commit(String userId, String requestId);

    QuotaSnapshot release(String userId, String requestId);

    QuotaSnapshot getSnapshot(String userId);

    int releaseExpired(Instant cutoff);
}
