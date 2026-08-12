package cn.bugstack.ai.api.dto;

public record QuotaResponseDTO(
        int freeGranted,
        int purchasedGranted,
        int consumed,
        int reserved,
        int remaining
) {
}
