package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiQuotaLedgerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IAiQuotaLedgerDao {

    int insertIgnoreFreeGrant(AiQuotaLedgerPO ledger);

    AiQuotaLedgerPO selectByUserAndRequestForUpdate(
            @Param("userId") String userId,
            @Param("requestId") String requestId
    );

    int insertReservation(AiQuotaLedgerPO ledger);

    int reactivateReleased(
            @Param("userId") String userId,
            @Param("requestId") String requestId,
            @Param("agentId") String agentId,
            @Param("endpoint") String endpoint
    );

    int commitReservation(
            @Param("userId") String userId,
            @Param("requestId") String requestId
    );

    int releaseReservation(
            @Param("userId") String userId,
            @Param("requestId") String requestId
    );

    List<AiQuotaLedgerPO> selectExpiredReservations(
            @Param("cutoff") LocalDateTime cutoff
    );

    AiQuotaLedgerPO selectExpiredReservationForUpdate(
            @Param("userId") String userId,
            @Param("requestId") String requestId,
            @Param("cutoff") LocalDateTime cutoff
    );
}
