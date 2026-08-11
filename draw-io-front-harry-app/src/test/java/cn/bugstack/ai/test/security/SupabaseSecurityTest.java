package cn.bugstack.ai.test.security;

import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.trigger.http.AgentServiceController;
import cn.bugstack.ai.trigger.security.AuthenticatedUserProvider;
import cn.bugstack.ai.trigger.security.JsonAccessDeniedHandler;
import cn.bugstack.ai.trigger.security.JsonAuthenticationEntryPoint;
import cn.bugstack.ai.trigger.security.SupabaseSecurityConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;


@RunWith(SpringRunner.class)
// 只启动 Web 相关部分，不启动完整项目、数据库和真实模型
// 本测试只需要加载这个 Controller
@WebMvcTest(controllers = AgentServiceController.class)
@Import({
        SupabaseSecurityConfig.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        AuthenticatedUserProvider.class
})
@TestPropertySource(properties = {
        "supabase.auth.issuer=https://test-project.invalid/auth/v1",
        "supabase.auth.jwks-uri=http://127.0.0.1:9/jwks",
        "supabase.auth.allowed-user-ids=22222222-2222-2222-2222-222222222222"
})
public class SupabaseSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    // 主应用配置中的 testTools() 依赖它。它与当前测试无关，只是为了让测试环境能成功启动
    @MockitoBean
    private MyTestMcpService myTestMcpService;

    // Controller 依赖 IChatService，但 Web 切片不会加载真实业务服务，所以提供一个假的对象
    @MockitoBean
    private IChatService chatService;

    @Test
    public void shouldReturnJson401WhenTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/query_ai_agent_config_list"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    public void shouldReturnJson403WhenUserIsNotAllowed() throws Exception {
        when(chatService.queryAiAgentConfigList()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/query_ai_agent_config_list")
                        .with(jwt().jwt(token -> token
                                .subject("11111111-1111-1111-1111-111111111111")
                                .claim("role", "authenticated"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AI_USER_NOT_ALLOWED"));
    }

    @Test
    public void shouldAllowUserInAllowlist() throws Exception {
        when(chatService.queryAiAgentConfigList()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/query_ai_agent_config_list")
                        .with(jwt().jwt(token -> token
                                .subject("22222222-2222-2222-2222-222222222222")
                                .claim("role", "authenticated"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"));
    }

}
