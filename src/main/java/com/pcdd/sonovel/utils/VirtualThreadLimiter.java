package com.pcdd.sonovel.utils;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * 使用 Semaphore 控制虚拟线程并发量的工具类
 *
 * @author pcdd
 * Created at 2025/9/18
 */
public class VirtualThreadLimiter implements AutoCloseable {

    private final Semaphore semaphore;
    private final ExecutorService executor;
    private final Phaser phaser = new Phaser(1);

    public VirtualThreadLimiter(int maxConcurrent) {
        this.semaphore = new Semaphore(maxConcurrent);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 提交受限任务
     *
     * @param task 待执行的任务
     */
    public void submit(Runnable task) {
        phaser.register();

        executor.submit(() -> {
            try {
                semaphore.acquire();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                semaphore.release();
                phaser.arriveAndDeregister();
            }
        });
    }

    /**
     * 在取消前取得许可并提交任务；返回 false 表示已取消（任务未提交）。
     * <p>
     * 与 {@link #submit(Runnable)} 的区别：{@code submit} 会把任务全部入队再自行排队等许可，
     * 调用方一瞬间就能把上千个任务投完；这里让调用方跟着并发上限走，便于在派发循环里及时感知取消。
     */
    public boolean trySubmitAndWait(Runnable task, BooleanSupplier cancelled) {
        phaser.register();
        try {
            // 每轮先检查取消，再尝试取许可：否则许可恰好空闲时会一直派发下去
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    phaser.arriveAndDeregister();
                    return false;
                }
                if (semaphore.tryAcquire(200, TimeUnit.MILLISECONDS)) break;
            }
        } catch (InterruptedException e) {
            phaser.arriveAndDeregister();
            Thread.currentThread().interrupt();
            return false;
        }

        executor.submit(() -> {
            try {
                task.run();
            } finally {
                semaphore.release();
                phaser.arriveAndDeregister();
            }
        });
        return true;
    }

    /**
     * 优雅关闭
     */
    @Override
    public void close() {
        phaser.arriveAndAwaitAdvance();

        executor.shutdown();

        try {
            if (!executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}