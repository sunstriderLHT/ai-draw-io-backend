package cn.bugstack.ai.domain.quota.service;

import cn.bugstack.ai.domain.quota.model.entity.QuotaReservationEntity;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;

import java.time.Instant;

public interface IAiQuotaService {

    QuotaReservationEntity reserve(
            String userId,
            String requestId,
            String agentId,
            String endpoint
    );

    QuotaSnapshotEntity commit(String userId, String requestId);

    QuotaSnapshotEntity release(String userId, String requestId);

    QuotaSnapshotEntity getSnapshot(String userId);

    int releaseExpired(Instant cutoff);
}
