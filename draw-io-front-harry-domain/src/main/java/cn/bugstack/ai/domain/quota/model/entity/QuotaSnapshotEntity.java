package cn.bugstack.ai.domain.quota.model.entity;

public record QuotaSnapshotEntity(
        int freeGranted,
        int purchasedGranted,
        int consumed,
        int reserved
) {

    // 剩余次数 = 免费赠送 + 购买次数 - 已消耗 - 已预占
    public int remaining() {
        return freeGranted + purchasedGranted - consumed - reserved;
    }
}