package com.hmdp.config;

import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.AccountPassword;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 仅由管理员在本机交互运行，用于已有无密码账号的首次密码设置。没有 HTTP 入口。 */
@Component
@Profile("account-setup")
public class AccountSetupRunner implements ApplicationRunner {
    private final IUserService users;
    private final ConfigurableApplicationContext context;
    public AccountSetupRunner(IUserService users, ConfigurableApplicationContext context) {
        this.users = users; this.context = context;
    }
    public void run(ApplicationArguments args) {
        try {
            if (args.containsOption("account-setup.create")) {
                String account = args.getOptionValues("account-setup.create").get(0);
                if (!account.matches("[A-Za-z0-9_]{4,11}")) throw new IllegalArgumentException("账号需为4至11位字母、数字或下划线");
                String password = System.getenv("ACCOUNT_SETUP_PASSWORD");
                User user = new User().setPhone(account).setNickName("本地测试商家").setPassword(AccountPassword.encode(password));
                if (!users.save(user)) throw new IllegalStateException("创建失败");
                System.out.println("CREATED_MERCHANT_USER_ID=" + user.getId());
                return;
            }
            java.io.Console console = System.console();
            if (console == null) throw new IllegalStateException("请在本机终端交互运行账号初始化命令");
            long id = Long.parseLong(console.readLine("已有用户 ID: "));
            User user = users.getById(id);
            if (user == null) throw new IllegalArgumentException("用户不存在，不会自动创建账号");
            if (user.getPassword() != null && !user.getPassword().isBlank()) throw new IllegalStateException("该账号已有密码，不会覆盖");
            char[] first = console.readPassword("设置密码（12–128 字符）: ");
            char[] second = console.readPassword("再次输入: ");
            try {
                if (first == null || second == null || !java.util.Arrays.equals(first, second)) throw new IllegalArgumentException("密码不一致");
                String encoded = AccountPassword.encode(new String(first));
                boolean saved = users.lambdaUpdate().eq(User::getId, id)
                        .and(q -> q.isNull(User::getPassword).or().eq(User::getPassword, ""))
                        .set(User::getPassword, encoded).update();
                if (!saved) throw new IllegalStateException("账号状态已变化，未设置密码");
                console.printf("密码设置成功。商家店铺权限仍需由管理员单独配置。%n");
            } finally {
                if (first != null) java.util.Arrays.fill(first, '\0');
                if (second != null) java.util.Arrays.fill(second, '\0');
            }
        } finally { context.close(); }
    }
}
