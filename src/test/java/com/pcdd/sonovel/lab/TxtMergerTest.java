package com.pcdd.sonovel.lab;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileAppender;
import com.pcdd.sonovel.utils.FileUtils;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 测试结论：多线程无法提高 I/O 密集型任务的性能（epub 章节的添加、多个 txt 合并）
 * <p>
 * 注意：这是基准测试，会把章节文件合并成一个大 txt，非常吃磁盘。
 * 因此输出固定放在 {@code d:/txt单章目录/_bench/}（与输入目录隔离），
 * 且输入为空时直接跳过——否则“输出文件本身也被当输入”会自我追加、指数膨胀。
 *
 * @author pcdd
 * Created at 2024/12/8
 */

class TxtMergerTest {

    static File dir = FileUtil.file("d:/txt单章目录");
    static File outputDir = FileUtil.file("d:/txt单章目录/_bench");

    /** 待合并的输入文件：排除输出目录，避免把自己的产物再当输入 */
    private static List<File> inputs() {
        if (!dir.exists() || !dir.isDirectory()) return List.of();
        return FileUtils.sortFilesByName(dir).stream()
                .filter(f -> f.isFile() && !f.getAbsolutePath().startsWith(outputDir.getAbsolutePath()))
                .filter(f -> f.getName().toLowerCase().endsWith(".txt"))
                .toList();
    }

    private static File output(String name) {
        return FileUtil.file(outputDir, name);
    }

    @BeforeEach
    void requireInputs() {
        List<File> files = inputs();
        Assumptions.assumeFalse(files.isEmpty(),
                "d:/txt单章目录 下没有可合并的 txt，跳过基准测试（该测试会把内容合并成一个大文件，很吃磁盘）");
    }

    @Test
    @DisplayName("FileAppender 追加")
    void test01() {
        File outputFile = output("merged-01.txt");
        FileAppender appender = new FileAppender(outputFile, 16, true);
        for (File item : inputs()) {
            System.out.println(item.getName());
            appender.append(FileUtil.readUtf8String(item));
        }
        appender.flush();
    }

    @Test
    @SneakyThrows
    @DisplayName("BufferedWriter 逐行")
    void test02() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output("merged-02.txt"), StandardCharsets.UTF_8, true))) {
            for (File item : inputs()) {
                System.out.println(item.getName());
                try (BufferedReader reader = new BufferedReader(new FileReader(item, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
                writer.newLine();
            }
        }
    }

    @Test
    @SneakyThrows
    @DisplayName("test02 的优化")
    void test03() {
        List<File> files = inputs();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output("merged-03.txt"), StandardCharsets.UTF_8, true), 16 * 1024)) {
            for (File item : files) {
                System.out.println("Processing: " + item.getName());
                try (BufferedReader reader = new BufferedReader(new FileReader(item, StandardCharsets.UTF_8), 16 * 1024)) {
                    char[] buffer = new char[16 * 1024];
                    int len;
                    while ((len = reader.read(buffer)) != -1) {
                        writer.write(buffer, 0, len);
                    }
                }
                writer.newLine();
            }
        }
    }

    @Test
    @DisplayName("NIO")
    @SneakyThrows
    void test04() {
        Path outputPath = Paths.get(output("merged-04.txt").getAbsolutePath());
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (File item : inputs()) {
                System.out.println("Processing: " + item.getName());
                Files.lines(item.toPath(), StandardCharsets.UTF_8).forEach(line -> {
                    try {
                        writer.write(line);
                        writer.newLine();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                writer.newLine();
            }
        }
    }

    @Test
    @DisplayName("FileChannel")
    @SneakyThrows
    void test05() {
        Path outputPath = Paths.get(output("merged-05.txt").getAbsolutePath());
        try (FileChannel outChannel = FileChannel.open(outputPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            for (File file : inputs()) {
                System.out.println("Processing: " + file.getName());
                try (FileChannel inChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                    long size = inChannel.size();
                    MappedByteBuffer buffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, size);
                    outChannel.write(buffer);
                }
                outChannel.write(ByteBuffer.wrap(System.lineSeparator().getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

}
