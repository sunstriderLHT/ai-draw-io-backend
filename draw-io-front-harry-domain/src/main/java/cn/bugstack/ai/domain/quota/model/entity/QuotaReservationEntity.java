package cn.bugstack.ai.domain.quota.model.entity;

import cn.bugstack.ai.domain.quota.model.valobj.QuotaLedgerStatus;

public record QuotaReservationEntity (
        String requestId,
        QuotaLedgerStatus status,
        QuotaSnapshotEntity snapshot
) {
}