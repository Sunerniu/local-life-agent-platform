package com.hmdp.agent;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.AccountPassword;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordLoginTest {
    UserServiceImpl users;
    StringRedisTemplate redis;
    ValueOperations<String,String> values;
    HashOperations<String,Object,Object> hashes;
    QueryChainWrapper<User> query;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        users = spy(new UserServiceImpl()); redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class); hashes = mock(HashOperations.class); query = mock(QueryChainWrapper.class);
        ReflectionTestUtils.setField(users,"stringRedisTemplate",redis);
        when(redis.opsForValue()).thenReturn(values); when(redis.opsForHash()).thenReturn(hashes);
        when(values.increment(anyString())).thenReturn(1L);
        doReturn(query).when(users).query(); when(query.eq("phone","merchant01")).thenReturn(query);
    }
    @Test void correctPasswordCreatesExistingSessionFormat() {
        when(query.one()).thenReturn(new User().setId(99L).setPhone("merchant01").setNickName("测试商家")
                .setPassword(AccountPassword.encode("test-password-1234")));
        Result result = users.loginWithPassword("merchant01","test-password-1234");
        assertTrue(result.getSuccess()); assertNotNull(result.getData());
        verify(hashes).putAll(eq("login:token:" + result.getData()),argThat(map -> "99".equals(map.get("id")) && !map.containsKey("password")));
    }
    @Test void unknownUserAndUnsetPasswordDoNotCreateSessions() {
        when(query.one()).thenReturn(null, new User().setId(99L).setPassword(""));
        assertFalse(users.loginWithPassword("merchant01","test-password-1234").getSuccess());
        assertFalse(users.loginWithPassword("merchant01","test-password-1234").getSuccess());
        verifyNoInteractions(hashes);
    }
    @Test void attemptsAreLimitedBeforePasswordValidation() {
        when(values.increment(anyString())).thenReturn(11L);
        assertFalse(users.loginWithPassword("merchant01","test-password-1234").getSuccess());
        verify(query,never()).one(); verifyNoInteractions(hashes);
    }
    @Test void logoutRevokesToken() {
        assertTrue(users.logout("session123").getSuccess());
        verify(redis).delete("login:token:session123");
    }
}
