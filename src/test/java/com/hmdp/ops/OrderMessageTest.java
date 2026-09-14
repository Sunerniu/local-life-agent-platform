package com.hmdp.ops;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.OrderMessageService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OrderMessageTest {
    final OrderMessageService parser=new OrderMessageService(new ObjectMapper());
    @Test void ignoresPaymentAndStatusAndRejectsMalformedIdentities() {
        var order=parser.parse("{\"id\":10,\"userId\":7,\"voucherId\":1,\"status\":3,\"payType\":2}".getBytes());
        assertNull(order.getStatus());assertNull(order.getPayType());
        for(String body:new String[]{"{}","[]","{\"id\":1,\"id\":2,\"userId\":7,\"voucherId\":1}","{\"id\":-1,\"userId\":7,\"voucherId\":1}","{\"id\":1,\"userId\":7,\"voucherId\":1} {}"})
            assertThrows(IllegalArgumentException.class,()->parser.parse(body.getBytes()));
    }
}
