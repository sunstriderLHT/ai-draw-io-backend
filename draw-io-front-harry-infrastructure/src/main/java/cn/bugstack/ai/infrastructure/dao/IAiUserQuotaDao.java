package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiUserQuotaPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAiUserQuotaDao {

    int upsertAccount(
            @Param("userId") String userId,
            @Param("freeQuota") int freeQuota
    );

    AiUserQuotaPO selectAccount(
            @Param("userId") String userId
    );

    AiUserQuotaPO selectAccountForUpdate(
            @Param("userId") String userId
    );

    int incrementReserved(
            @Param("userId") String userId
    );

    int commitReserved(
            @Param("userId") String userId
    );

    int releaseReserved(
            @Param("userId") String userId
    );
}
