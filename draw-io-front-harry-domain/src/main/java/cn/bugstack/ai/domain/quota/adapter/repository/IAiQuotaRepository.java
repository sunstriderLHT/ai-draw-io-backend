package cn.bugstack.ai.domain.quota.adapter.repository;

import cn.bugstack.ai.domain.quota.model.entity.QuotaReservationEntity;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshot;

import java.time.Instant;

public interface IAiQuotaRepository {

    QuotaReservationEntity reserve(
            String userId,
            String requestId,
            String agentId,
            String endpoint,
            int freeQuota
    );

    QuotaSnapshot commit(String userId, String requestId);

    QuotaSnapshot release(String userId, String requestId);

    QuotaSnapshot findOrCreate(String userId, int freeQuota);

    int releaseExpired(Instant cutoff);
}
