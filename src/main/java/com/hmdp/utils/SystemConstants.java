package com.hmdp.utils;

public class SystemConstants {
    //nginx 的实际目录，上传的图片要落到 nginx 的 html/hmdp/imgs 下，前端才能通过 /imgs 访问到
    public static final String IMAGE_UPLOAD_DIR = "E:\\hmdp\\nginx-1.18.0\\nginx-1.18.0\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
