package com.hmdp.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadPool {
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(5);

    public static void submitTask(Runnable task) {
        EXECUTOR_SERVICE.submit(task);
    }

    public static void shutdownThreadPool() {
        if (!EXECUTOR_SERVICE.isShutdown()) {
            EXECUTOR_SERVICE.shutdown();
        }
    }
}
