package com.hmdp.ops;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ops")
public class OpsProperties {
    private boolean enabled;
    private String environment = "local";
    private Set<Long> diagnosticians = new HashSet<>();
    private Set<Long> operators = new HashSet<>();
}
