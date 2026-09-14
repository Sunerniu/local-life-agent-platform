package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import org.springframework.stereotype.Service;

/** 消息仅携带订单身份，不允许通过消息覆盖支付或订单状态字段。 */
@Service
public class OrderMessageService {
    private final ObjectMapper json;
    public OrderMessageService(ObjectMapper json) { this.json=json; }
    public VoucherOrder parse(byte[] body) {
        try {
            if(body == null || body.length>16384) throw new IllegalArgumentException();
            var value=json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            for(String key: new String[]{"id","userId","voucherId"})
                if(!value.path(key).isIntegralNumber() || !value.path(key).canConvertToLong() || value.path(key).asLong()<=0)
                    throw new IllegalArgumentException();
            return new VoucherOrder().setId(value.get("id").asLong()).setUserId(value.get("userId").asLong()).setVoucherId(value.get("voucherId").asLong());
        } catch(Exception ex) { throw new IllegalArgumentException("INVALID_ORDER_MESSAGE"); }
    }
}
