package com.hmdp.utils;

public class SystemConstants {
    /**
     * 原先这里硬编码了 Windows 路径 E:\javaweb\...\imgs，换机器就失效。
     * 现已移到配置项 hmdp.upload.dir（见 application.yaml），由 UploadController 注入。
     */
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
