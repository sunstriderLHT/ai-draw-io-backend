package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.quota.adapter.repository.IAiQuotaRepository;
import cn.bugstack.ai.domain.quota.exception.QuotaExhaustedException;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestAlreadyCompletedException;
import cn.bugstack.ai.domain.quota.exception.QuotaRequestInProgressException;
import cn.bugstack.ai.domain.quota.model.entity.QuotaReservationEntity;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.domain.quota.model.valobj.QuotaLedgerStatus;
import cn.bugstack.ai.infrastructure.dao.IAiQuotaLedgerDao;
import cn.bugstack.ai.infrastructure.dao.IAiUserQuotaDao;
import cn.bugstack.ai.infrastructure.dao.po.AiQuotaLedgerPO;
import cn.bugstack.ai.infrastructure.dao.po.AiUserQuotaPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Repository
public class MySqlAiQuotaRepository implements IAiQuotaRepository {

    private static final String FREE_GRANT_REQUEST_ID = "FREE_GRANT";

    private final IAiUserQuotaDao userQuotaDao;
    private final IAiQuotaLedgerDao quotaLedgerDao;

    public MySqlAiQuotaRepository(
            IAiUserQuotaDao userQuotaDao,
            IAiQuotaLedgerDao quotaLedgerDao
    ) {
        this.userQuotaDao = userQuotaDao;
        this.quotaLedgerDao = quotaLedgerDao;
    }

    @Override
    @Transactional
    public QuotaSnapshotEntity findOrCreate(String userId, int freeQuota) {
        userQuotaDao.upsertAccount(userId, freeQuota);
        quotaLedgerDao.insertIgnoreFreeGrant(
                AiQuotaLedgerPO.builder()
                        .requestId(FREE_GRANT_REQUEST_ID)
                        .userId(userId)
                        .endpoint("system")
                        .entryType("FREE_GRANT")
                        .amount(freeQuota)
                        .status("COMMITTED")
                        .sourceRef("initial-free-quota")
                        .build()
        );

        AiUserQuotaPO account = userQuotaDao.selectAccount(userId);
        return toSnapshot(account);
    }

    // 将PO转化为领域对象
    private QuotaSnapshotEntity toSnapshot(AiUserQuotaPO account) {
        return new QuotaSnapshotEntity(
                account.getFreeGranted(),
                account.getPurchasedGranted(),
                account.getConsumed(),
                account.getReserved()
        );
    }

    @Override
    @Transactional
    public QuotaReservationEntity reserve(
            String userId,
            String requestId,
            String agentId,
            String endpoint,
            int freeQuota
    ) {
        // 初始化账号，如果新用户免费额度发放
        initializeAccount(userId, freeQuota);
        // 查询出用户额度快照
        AiUserQuotaPO account = userQuotaDao.selectAccountForUpdate(userId);
        // 查看当前requestId + userId流水
        AiQuotaLedgerPO existing =
                quotaLedgerDao.selectByUserAndRequestForUpdate(userId, requestId);
        // 流水存在时，RESERVED & COMMITTED 抛异常
        if (existing != null) {
            if (QuotaLedgerStatus.RESERVED.name().equals(existing.getStatus())) {
                throw new QuotaRequestInProgressException();
            }
            if (QuotaLedgerStatus.COMMITTED.name().equals(existing.getStatus())) {
                throw new QuotaRequestAlreadyCompletedException();
            }
        }
        // 流水不存在时 或 RELEASED
        // 查看额度快照领域实体
        QuotaSnapshotEntity lockedSnapshot = toSnapshot(account);
        // 没额度了抛异常
        if (lockedSnapshot.remaining() <= 0) {
            throw new QuotaExhaustedException(lockedSnapshot);
        }
        // 有额度，预扣
        int accountUpdated = userQuotaDao.incrementReserved(userId);
        if (accountUpdated != 1) {
            throw new IllegalStateException("failed to reserve AI quota");
        }
        // 流水不存在时，新增流水，状态标记为RESERVED
        if (existing == null) {
            quotaLedgerDao.insertReservation(
                    AiQuotaLedgerPO.builder()
                            .requestId(requestId)
                            .userId(userId)
                            .agentId(agentId)
                            .endpoint(endpoint)
                            .entryType("CHAT_USAGE")
                            .amount(-1)
                            .status(QuotaLedgerStatus.RESERVED.name())
                            .build()
            );
            // 流水状态为 RELEASED，调整流水信息
        } else {
            int ledgerUpdated = quotaLedgerDao.reactivateReleased(
                    userId,
                    requestId,
                    agentId,
                    endpoint
            );
            if (ledgerUpdated != 1) {
                throw new IllegalStateException("failed to reactivate AI quota reservation");
            }
        }
        // 获取到预扣额度后的额度快照
        QuotaSnapshotEntity snapshot =
                toSnapshot(userQuotaDao.selectAccountForUpdate(userId));
        return new QuotaReservationEntity(
                requestId,
                QuotaLedgerStatus.RESERVED,
                snapshot
        );
    }

    private void initializeAccount(String userId, int freeQuota) {
        userQuotaDao.upsertAccount(userId, freeQuota);

        quotaLedgerDao.insertIgnoreFreeGrant(
                AiQuotaLedgerPO.builder()
                        .requestId(FREE_GRANT_REQUEST_ID)
                        .userId(userId)
                        .endpoint("system")
                        .entryType("FREE_GRANT")
                        .amount(freeQuota)
                        .status(QuotaLedgerStatus.COMMITTED.name())
                        .sourceRef("initial-free-quota")
                        .build()
        );
    }

    @Override
    public QuotaSnapshotEntity commit(
            String userId,
            String requestId
    ) {
        AiUserQuotaPO account = userQuotaDao.selectAccountForUpdate(userId);

        if (account == null) {
            throw new IllegalStateException("quota account does not exist");
        }
        AiQuotaLedgerPO ledger = quotaLedgerDao.selectByUserAndRequestForUpdate(userId, requestId);

        if (ledger == null) {
            throw new IllegalStateException("quota reservation does not exist");
        }

        if (QuotaLedgerStatus.COMMITTED.name()
                .equals(ledger.getStatus())) {
            throw new QuotaRequestAlreadyCompletedException();
        }

        int accountUpdated = userQuotaDao.commitReserved(userId);

        if (accountUpdated != 1) {
            throw new IllegalStateException("failed to commit quota account");
        }

        int ledgerUpdated = quotaLedgerDao.commitReservation(userId, requestId);

        if (ledgerUpdated != 1) {
            throw new IllegalStateException("failed to commit quota ledger");
        }

        return toSnapshot(userQuotaDao.selectAccountForUpdate(userId));


    }

    @Override
    @Transactional
    public QuotaSnapshotEntity release(
            String userId,
            String requestId
    ) {
        AiUserQuotaPO account = userQuotaDao.selectAccountForUpdate(userId);

        if (account == null) {
            throw new IllegalStateException("quota account does not exist");
        }

        AiQuotaLedgerPO ledger = quotaLedgerDao.selectByUserAndRequestForUpdate(userId, requestId);

        if (ledger == null) {
            throw new IllegalStateException("quota reservation does not exist");
        }

        if (QuotaLedgerStatus.RELEASED.name().equals(ledger.getStatus())) {
            return toSnapshot(account);
        }

        if (QuotaLedgerStatus.COMMITTED.name().equals(ledger.getStatus())) {
            throw new QuotaRequestAlreadyCompletedException();
        }

        if (!QuotaLedgerStatus.RESERVED.name().equals(ledger.getStatus())) {
            throw new IllegalStateException("quota reservation is not active");
        }

        int accountUpdated = userQuotaDao.releaseReserved(userId);

        if (accountUpdated != 1) {
            throw new IllegalStateException("failed to release quota account");
        }

        int ledgerUpdated = quotaLedgerDao.releaseReservation(userId, requestId);

        if (ledgerUpdated != 1) {
            throw new IllegalStateException("failed to release quota ledger");
        }

        return toSnapshot(userQuotaDao.selectAccountForUpdate(userId));
    }

    @Override
    @Transactional
    public int releaseExpired(Instant cutoff) {
        LocalDateTime cutoffUtc =
                LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC);

        List<AiQuotaLedgerPO> candidates =
                quotaLedgerDao.selectExpiredReservations(cutoffUtc);


        int released = 0;

        for (AiQuotaLedgerPO candidate : candidates) {
            String userId = candidate.getUserId();
            String requestId = candidate.getRequestId();

            AiUserQuotaPO account =
                    userQuotaDao.selectAccountForUpdate(userId);

            if (account == null) {
                continue;
            }

            AiQuotaLedgerPO locked =
                    quotaLedgerDao
                            .selectExpiredReservationForUpdate(
                                    userId,
                                    requestId,
                                    cutoffUtc
                            );

            if (locked == null) {
                continue;
            }

            int accountUpdated = userQuotaDao.releaseReserved(userId);

            if (accountUpdated != 1) {
                throw new IllegalStateException("failed to recover expired quota account");
            }

            int ledgerUpdated = quotaLedgerDao.releaseReservation(userId, requestId);

            if (ledgerUpdated != 1) {
                throw new IllegalStateException("failed to recover expired quota ledger");
            }

            released++;
        }

        return released;
    }
}
