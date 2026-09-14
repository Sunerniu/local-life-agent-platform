package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    /**
     * 探店图片落盘目录，配置项 hmdp.upload.dir。
     * 原来是写死的 Windows 路径，现在跟着配置走，跨平台可用。
     */
    @Value("${hmdp.upload.dir}")
    private String uploadDir;

    @PostConstruct
    public void init() {
        // 防止 HMDP_UPLOAD_DIR 被设成空串把默认值覆盖掉
        if (uploadDir == null || uploadDir.trim().isEmpty()) {
            uploadDir = System.getProperty("user.dir") + "/nginx-1.18.0/html/hmdp/imgs";
            log.warn("hmdp.upload.dir 为空，回退到默认目录：{}", uploadDir);
        }
        File dir = new File(uploadDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("图片上传目录创建失败，请检查配置 hmdp.upload.dir：{}", uploadDir);
        } else {
            log.info("图片上传目录：{}", dir.getAbsolutePath());
        }
    }

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            image.transferTo(new File(uploadDir, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file = new File(uploadDir, filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(uploadDir, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
