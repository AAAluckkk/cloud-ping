package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

/**
 * @author 虎哥
 */
public class RegexUtils {

    /**
     * 手机号要求的长度
     */
    private static final int PHONE_LENGTH = 11;

    /**
     * 是否是无效手机格式。
     * <p>
     * 只校验「长度是不是 11 位」，不再校验运营商号段。
     * 原来的 PHONE_REGEX 要求必须以 1[38]/14x/15x/166/17x/19[89] 开头，
     * 学习/测试时造号不方便，所以放开了。
     *
     * @param phone 要校验的手机号
     * @return true:无效，false：有效
     */
    public static boolean isPhoneInvalid(String phone){
        if (StrUtil.isBlank(phone)) {
            return true;
        }
        return phone.length() != PHONE_LENGTH;
    }
    /**
     * 是否是无效邮箱格式
     * @param email 要校验的邮箱
     * @return true:符合，false：不符合
     */
    public static boolean isEmailInvalid(String email){
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    /**
     * 是否是无效验证码格式
     * @param code 要校验的验证码
     * @return true:符合，false：不符合
     */
    public static boolean isCodeInvalid(String code){
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    // 校验是否不符合正则格式
    private static boolean mismatch(String str, String regex){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}
