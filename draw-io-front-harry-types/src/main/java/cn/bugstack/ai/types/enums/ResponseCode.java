package cn.bugstack.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NOT_FOUND_METHOD("0003", "不存在的方法"),
    AI_QUOTA_EXHAUSTED("0004", "额度不足"),
    AI_REQUEST_IN_PROGRESS("0005", "相同请求正在处理中"),
    AI_REQUEST_ALREADY_COMPLETED("0006", "相同请求已处理完成"),
    AI_EMPTY_RESPONSE("0007", "模型没有返回有效内容"),

    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围"),
    ;

    private String code;
    private String info;

}
