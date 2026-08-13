package cn.bugstack.ai.domain.quota.adapter.repository;

import cn.bugstack.ai.domain.quota.model.entity.QuotaReservationEntity;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;

import java.time.Instant;

public interface IAiQuotaRepository {

    QuotaReservationEntity reserve(
            String userId,
            String requestId,
            String agentId,
            String endpoint,
            int freeQuota
    );

    QuotaSnapshotEntity commit(String userId, String requestId);

    QuotaSnapshotEntity release(String userId, String requestId);

    QuotaSnapshotEntity findOrCreate(String userId, int freeQuota);

    int releaseExpired(Instant cutoff);
}
