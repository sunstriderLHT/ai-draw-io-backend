package cn.bugstack.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatResponseDTO {

    private String content;

    private List<Trace> traces;

    private Integer remaining;

    @Data
    public static class Trace {
        private String agentName;
        private String content;
        private boolean completed;
    }
}
