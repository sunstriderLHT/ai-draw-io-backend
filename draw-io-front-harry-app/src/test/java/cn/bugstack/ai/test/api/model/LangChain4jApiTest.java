package cn.bugstack.ai.test.api.model;

import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LangChain4jApiTest {

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com/")
                .apiKey("sk-7922e7bb47e040d78d91cd87f2efce95")
                .modelName("deepseek-v4-flash")
                .build();

        String chat = model.chat("hi, 你好哇");
        log.info("测试结果：{}", chat);
    }
}
