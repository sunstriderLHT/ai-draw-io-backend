package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.AiAgentConfigResponseDTO;
import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ChatResponseDTO;
import cn.bugstack.ai.api.dto.CreateSessionRequestDTO;
import cn.bugstack.ai.api.dto.CreateSessionResponseDTO;
import cn.bugstack.ai.api.dto.QuotaResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.trigger.security.AuthenticatedUserProvider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private AgentStreamHeartbeatScheduler heartbeatScheduler;

    @Resource
    private AuthenticatedUserProvider authenticatedUserProvider;

    @Resource
    private MeteredAgentChatFacade meteredAgentChatFacade;

    @Resource
    private IAiQuotaService quotaService;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)

    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");
            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();
            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentId(agentConfig.getAgentId());
                return responseDTO;
            }).toList();

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getCode())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {

        String userId = authenticatedUserProvider.requireUserId();

        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), userId);
            String sessionId = chatService.createSession(requestDTO.getAgentId(), userId);

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("创建会话异常 agentId:{}", requestDTO.getAgentId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{}", requestDTO.getAgentId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(
            value = "quota",
            method = RequestMethod.GET
    )
    @Override
    public Response<QuotaResponseDTO> queryQuota() {
        String userId = authenticatedUserProvider.requireUserId();

        QuotaSnapshotEntity snapshot = quotaService.getSnapshot(userId);

        QuotaResponseDTO responseDTO =
                new QuotaResponseDTO(
                        snapshot.freeGranted(),
                        snapshot.purchasedGranted(),
                        snapshot.consumed(),
                        snapshot.reserved(),
                        snapshot.remaining()
                );

        return Response.<QuotaResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseDTO)
                .build();
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestHeader("Idempotency-Key") String requestId,
                                          @RequestBody ChatRequestDTO requestDTO) {

        String userId = authenticatedUserProvider.requireUserId();
        log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), userId);
        validateIdempotencyKey(requestId);

        MeteredChatResult meteredResult =
                meteredAgentChatFacade.chat(
                        userId,
                        requestId,
                        requestDTO.getAgentId(),
                        requestDTO.getSessionId(),
                        requestDTO.getMessage()
                );

        AgentChatResultVO result = meteredResult.result();

        ChatResponseDTO responseDTO = new ChatResponseDTO();

        responseDTO.setContent(result.getContent());

        responseDTO.setTraces(
                result.getTraces().stream()
                        .map(trace -> {
                            ChatResponseDTO.Trace traceDTO =
                                    new ChatResponseDTO.Trace();

                            traceDTO.setAgentName(trace.getAgentName());
                            traceDTO.setContent(trace.getContent());
                            traceDTO.setCompleted(trace.isCompleted());

                            return traceDTO;
                        })
                        .toList());

        responseDTO.setRemaining(
                meteredResult.remaining()
        );

        return Response.<ChatResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseDTO)
                .build();
    }

    @RequestMapping(
            value = "chat_stream",
            method = RequestMethod.POST,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Override
    public ResponseBodyEmitter chatStream(@RequestHeader("Idempotency-Key") String requestId,
                                          @RequestBody ChatRequestDTO requestDTO) {
        String userId = authenticatedUserProvider.requireUserId();
        log.info(
                "流式对话 agentId:{} userId:{} sessionId:{}",
                requestDTO.getAgentId(),
                userId,
                requestDTO.getSessionId()
        );
        validateIdempotencyKey(requestId);

        SseEmitter emitter = new SseEmitter(20 * 60 * 1000L);
        AgentStreamLifecycle lifecycle = new AgentStreamLifecycle(
                emitter,
                requestDTO.getAgentId(),
                userId,
                requestDTO.getSessionId()
        );
        emitter.onCompletion(lifecycle::clientCompletedConnection);
        emitter.onTimeout(lifecycle::timeout);
        emitter.onError(lifecycle::clientDisconnected);

        try {
            lifecycle.registerHeartbeat(
                    heartbeatScheduler.schedule(lifecycle::heartbeat)
            );
            lifecycle.registerSubscription(
                    meteredAgentChatFacade.chatStream(
                            userId,
                            requestId,
                            requestDTO.getAgentId(),
                            requestDTO.getSessionId(),
                            requestDTO.getMessage()
                    ).subscribe(
                            meteredOutput -> lifecycle.send(
                                    SseEmitter.event()
                                            .name(
                                                    meteredOutput.output()
                                                            .getType()
                                                            .name()
                                                            .toLowerCase(Locale.ROOT)
                                            )
                                            .data(
                                                    meteredOutput,
                                                    MediaType.APPLICATION_JSON
                                            )
                            ),
                            lifecycle::fail,
                            lifecycle::completeNormally
                    ));
        } catch (Exception e) {
            lifecycle.fail(e);
        }
        return emitter;
    }

    private void validateIdempotencyKey(String requestId) {
        UUID parsed = UUID.fromString(requestId);

        if (!parsed.toString().equalsIgnoreCase(requestId)) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be a canonical UUID"
            );
        }
    }
}
