package com.hmdp.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "hmdp.agent")
public class AgentProperties {
    private boolean enabled;
    private String model = "";
    private String baseUrl = "https://api.openai.com/v1";
    private int maxRounds = 4;
    private int maxToolCalls = 8;
    private int approvalTtlSeconds = 600;
    private int maxApprovals = 500;
    // 服务端配置授权；模型输出、请求体不能提供身份或修改权限。
    private Map<Long, Grant> grants = new HashMap<>();

    @Data
    public static class Grant {
        private Set<Long> shopIds = new HashSet<>();
        private Set<String> permissions = new HashSet<>();
    }
}
