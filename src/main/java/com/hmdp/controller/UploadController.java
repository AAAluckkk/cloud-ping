package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    /**
     * 允许上传的后缀白名单。
     * imgs 这个目录是交给 nginx 当静态资源直接对外提供的，
     * 不做限制的话，传一个 .html 或 .svg 上去就是一个存储型 XSS
     */
    private static final List<String> ALLOWED_SUFFIX =
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /**
     * 上传探店笔记的图片，落在 imgs/blogs 下
     */
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        return saveImage(image, "blogs");
    }

    /**
     * 上传头像，落在 imgs/icons 下。
     * 返回形如 /icons/{d1}/{d2}/{uuid}.{后缀} 的路径，
     * 前端统一在前面拼 /imgs 才是能访问的 url
     */
    @PostMapping("icon")
    public Result uploadIcon(@RequestParam("file") MultipartFile image) {
        return saveImage(image, "icons");
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    /**
     * 校验后缀 + 落盘的公共逻辑
     */
    private Result saveImage(MultipartFile image, String type) {
        String originalFilename = image.getOriginalFilename();
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        if (StrUtil.isBlank(suffix) || !ALLOWED_SUFFIX.contains(suffix.toLowerCase())) {
            return Result.fail("只能上传图片文件");
        }
        try {
            String fileName = createNewFileName(suffix, type);
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 生成"按内容散列分两级目录"的存放路径，避免单个目录下文件过多。
     * type 传 blogs 时结果和原来完全一致（/blogs/{d1}/{d2}/{uuid}.{后缀}）
     */
    private String createNewFileName(String suffix, String type) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR,
                StrUtil.format("/{}/{}/{}", type, d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return StrUtil.format("/{}/{}/{}/{}.{}", type, d1, d2, name, suffix);
    }
}
