package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 编辑资料页提交上来的数据。
 * 页面是"点一行、改一项"的交互，所以每次请求只会带上其中一个字段，
 * 其余保持 null，Service 里靠判空来决定要不要更新。
 */
@Data
public class UserProfileDTO {

    /**
     * 昵称（存在 tb_user）
     */
    private String nickName;

    /**
     * 头像路径，形如 /imgs/icons/{d1}/{d2}/xxx.jpg（存在 tb_user）
     */
    private String icon;

    /**
     * 个人介绍（存在 tb_user_info）
     */
    private String introduce;

    /**
     * 性别，false：男，true：女
     */
    private Boolean gender;

    /**
     * 城市
     */
    private String city;

    /**
     * 生日
     */
    private LocalDate birthday;
}
