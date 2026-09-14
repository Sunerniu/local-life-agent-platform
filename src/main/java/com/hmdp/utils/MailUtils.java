package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.mail.Authenticator;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * 邮箱验证码发送工具。
 *
 * 账号与授权码原本硬编码在源码里（还提交进了 git），现已改为读配置：
 *   hmdp.mail.host / port / username / password
 * 对应环境变量 MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_PASSWORD，
 * 也可以写在 application-local.yaml（已 gitignore）里。
 *
 * 保留静态方法签名，调用方（UserServiceImpl）无需改动；
 * 配置通过 Spring 注入 setter 后写入静态字段。
 */
@Component
public class MailUtils {

    private static String smtpHost = "smtp.qq.com";
    private static String smtpPort = "587";
    private static String mailUser;
    private static String mailPassword;

    @Value("${hmdp.mail.host:smtp.qq.com}")
    public void setSmtpHost(String host) {
        MailUtils.smtpHost = host;
    }

    @Value("${hmdp.mail.port:587}")
    public void setSmtpPort(String port) {
        MailUtils.smtpPort = port;
    }

    @Value("${hmdp.mail.username:}")
    public void setMailUser(String user) {
        MailUtils.mailUser = user;
    }

    @Value("${hmdp.mail.password:}")
    public void setMailPassword(String password) {
        MailUtils.mailPassword = password;
    }

    /**
     * 发送验证码邮件
     */
    public static void sendtoMail(String email, String code) throws MessagingException {
        if (isBlank(mailUser) || isBlank(mailPassword)) {
            throw new IllegalStateException(
                    "邮箱未配置，无法发送验证码。请设置 hmdp.mail.username / hmdp.mail.password "
                            + "（即环境变量 MAIL_USERNAME / MAIL_PASSWORD，或写进 application-local.yaml）。"
                            + "密码填的是 QQ 邮箱 16 位 SMTP 授权码，不是登录密码。");
        }

        Properties props = new Properties();
        // SMTP 发送邮件必须进行身份验证
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", smtpPort);
        // 587 端口需要 STARTTLS
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.user", mailUser);
        props.put("mail.password", mailPassword);

        // 构建授权信息，用于进行SMTP进行身份验证
        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        props.getProperty("mail.user"), props.getProperty("mail.password"));
            }
        };
        // 使用环境属性和授权信息，创建邮件会话
        Session mailSession = Session.getInstance(props, authenticator);
        // 创建邮件消息
        MimeMessage message = new MimeMessage(mailSession);
        // 设置发件人
        message.setFrom(new InternetAddress(props.getProperty("mail.user")));
        // 设置收件人的邮箱
        message.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(email));
        // 设置邮件标题
        message.setSubject("验证码");
        // 设置邮件的内容体
        message.setContent("尊敬的用户:你好!\n注册验证码为:" + code + "(有效期为一分钟,请勿告知他人)",
                "text/html;charset=UTF-8");
        Transport.send(message);
    }

    /**
     * 产生一个 5 位验证码。
     * 去掉了数字 1、0（与字母 l、O 易混淆）
     */
    public static String achieveCode() {
        String[] beforeShuffle = new String[]{"2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F",
                "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "a",
                "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v",
                "w", "x", "y", "z"};
        // Arrays.asList 返回的是数组视图，shuffle 会直接打乱 beforeShuffle 本身
        List<String> list = Arrays.asList(beforeShuffle);
        Collections.shuffle(list);
        StringBuilder sb = new StringBuilder();
        for (String s : beforeShuffle) {
            sb.append(s);
        }
        return sb.substring(3, 8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
