package cn.bugstack.ai.domain.quota.service;

import cn.bugstack.ai.domain.quota.adapter.repository.IAiQuotaRepository;
import cn.bugstack.ai.domain.quota.model.entity.QuotaReservationEntity;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;

import java.time.Instant;
import java.util.UUID;

public class AiQuotaService implements IAiQuotaService {

    private final IAiQuotaRepository repository;
    private final int freeQuota;

    public AiQuotaService(IAiQuotaRepository repository, int freeQuota) {
        this.repository = repository;
        this.freeQuota = freeQuota;
    }

    @Override
    public QuotaReservationEntity reserve(
            String userId,
            String requestId,
            String agentId,
            String endpoint
    ) {
        UUID.fromString(userId);
        UUID.fromString(requestId);
        if (!"chat".equals(endpoint) && !"chat_stream".equals(endpoint)) {
            throw new IllegalArgumentException(
                    "unsupported quota endpoint: " + endpoint
            );
        }
        return repository.reserve(
                userId,
                requestId,
                agentId,
                endpoint,
                freeQuota
        );
    }

    @Override
    public QuotaSnapshotEntity commit(String userId, String requestId) {
        UUID.fromString(userId);
        UUID.fromString(requestId);

        return repository.commit(userId, requestId);
    }

    @Override
    public QuotaSnapshotEntity release(String userId, String requestId) {
        UUID.fromString(userId);
        UUID.fromString(requestId);

        return repository.release(userId, requestId);
    }

    @Override
    public QuotaSnapshotEntity getSnapshot(String userId) {
        UUID.fromString(userId);

        return repository.findOrCreate(userId, freeQuota);
    }

    @Override
    public int releaseExpired(Instant cutoff) {
        return repository.releaseExpired(cutoff);
    }

}
