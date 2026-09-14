package com.hmdp.agent;

import com.hmdp.utils.AccountPassword;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountPasswordTest {
    @Test void saltsPasswordsAndVerifiesOnlyCorrectInput() {
        String one = AccountPassword.encode("test-password-1234"), two = AccountPassword.encode("test-password-1234");
        assertNotEquals(one, two); assertTrue(one.length() <= 128);
        assertTrue(AccountPassword.matches(one, "test-password-1234"));
        assertFalse(AccountPassword.matches(one, "another-password"));
        assertFalse(AccountPassword.matches("plain-text", "plain-text"));
        assertFalse(AccountPassword.matches("salt@md5", "test-password-1234"));
        assertFalse(AccountPassword.matches("pbkdf2$999999999$bad$bad", "test-password-1234"));
        assertThrows(IllegalArgumentException.class, () -> AccountPassword.encode("short"));
    }
}
