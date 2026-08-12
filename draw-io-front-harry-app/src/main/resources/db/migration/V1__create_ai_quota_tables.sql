CREATE TABLE ai_user_quota (
                               user_id             CHAR(36)    NOT NULL,
                               free_granted        INT         NOT NULL DEFAULT 3,
                               purchased_granted   INT         NOT NULL DEFAULT 0,
                               consumed            INT         NOT NULL DEFAULT 0,
                               reserved            INT         NOT NULL DEFAULT 0,
                               version             BIGINT      NOT NULL DEFAULT 0,
                               created_at          DATETIME(3) NOT NULL,
                               updated_at          DATETIME(3) NOT NULL,
                               PRIMARY KEY (user_id),
                               CONSTRAINT chk_ai_user_quota_non_negative CHECK (
                                           free_granted >= 0
                                       AND purchased_granted >= 0
                                       AND consumed >= 0
                                       AND reserved >= 0
                                   )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_quota_ledger (
                                 id              BIGINT       NOT NULL AUTO_INCREMENT,
                                 request_id      VARCHAR(64)  NOT NULL,
                                 user_id         CHAR(36)     NOT NULL,
                                 agent_id        VARCHAR(128) NULL,
                                 endpoint        VARCHAR(32)  NOT NULL,
                                 entry_type      VARCHAR(32)  NOT NULL,
                                 amount          INT          NOT NULL,
                                 status          VARCHAR(16)  NOT NULL,
                                 source_ref      VARCHAR(128) NULL,
                                 created_at      DATETIME(3)  NOT NULL,
                                 updated_at      DATETIME(3)  NOT NULL,
                                 PRIMARY KEY (id),
                                 UNIQUE KEY uk_ai_quota_ledger_user_request (
                                                                             user_id,
                                                                             request_id
                                     ),
                                 KEY idx_ai_quota_ledger_user_created (
                                                                       user_id,
                                                                       created_at
                                     ),
                                 KEY idx_ai_quota_ledger_status_updated (
                                                                         status,
                                                                         updated_at
                                     )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;