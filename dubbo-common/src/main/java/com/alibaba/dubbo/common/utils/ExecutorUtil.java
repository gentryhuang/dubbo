/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.utils;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExecutorUtil {
    private static final Logger logger = LoggerFactory.getLogger(ExecutorUtil.class);
    private static final ThreadPoolExecutor shutdownExecutor = new ThreadPoolExecutor(0, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(100),
            new NamedThreadFactory("Close-ExecutorService-Timer", true));

    public static boolean isTerminated(Executor executor) {
        if (executor instanceof ExecutorService) {
            if (((ExecutorService) executor).isTerminated()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 优雅关闭线程池，禁止新的任务提交，将原有任务执行完毕
     * <p>
     * Use the shutdown pattern from:
     * https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html
     *
     * @param executor the Executor to shutdown
     * @param timeout  the timeout in milliseconds before termination
     */
    public static void gracefulShutdown(Executor executor, int timeout) {
        // 如果不是ExecutorService，或者已经关闭，则直接返回
        if (!(executor instanceof ExecutorService) || isTerminated(executor)) {
            return;
        }
        final ExecutorService es = (ExecutorService) executor;
        try {
            // Disable new tasks from being submitted
            // 禁止新的任务提交
            es.shutdown();
        } catch (SecurityException ex2) {
            return;
        } catch (NullPointerException ex2) {
            return;
        }

        // 等待原有任务执行完毕。若等待超时，就强制结束所有任务
        try {
            // Wait a while for existing tasks to terminate
            if (!es.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                es.shutdownNow();
            }

            // 发生 InterruptedException 异常，也强制结束所有任务
        } catch (InterruptedException ex) {
            es.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 如果没有关闭成功，就新开线程去关闭
        if (!isTerminated(es)) {
            newThreadToCloseExecutor(es);
        }
    }

    /**
     * 强制关闭，包括打断原有执行中的任务
     *
     * @param executor
     * @param timeout
     */
    public static void shutdownNow(Executor executor, final int timeout) {

        // 如果不是ExecutorService，或者已经关闭，则直接返回
        if (!(executor instanceof ExecutorService) || isTerminated(executor)) {
            return;
        }

        // 立即关闭，包括原有任务也打断
        final ExecutorService es = (ExecutorService) executor;
        try {
            es.shutdownNow();
        } catch (SecurityException ex2) {
            return;
        } catch (NullPointerException ex2) {
            return;
        }

        // 等待原有任务执行完毕。若等待超时，就强制结束所有任务
        try {
            es.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        // 若未关闭成功，则新开线程去关闭
        if (!isTerminated(es)) {
            newThreadToCloseExecutor(es);
        }
    }

    /**
     * 新开线程关闭线程池
     *
     * @param es
     */
    private static void newThreadToCloseExecutor(final ExecutorService es) {
        if (!isTerminated(es)) {
            shutdownExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 循环1000次，不断强制结束线程池
                        for (int i = 0; i < 1000; i++) {
                            // 立即关闭，包括原有任务也打断
                            es.shutdownNow();

                            // 等待原有任务被打断完成
                            if (es.awaitTermination(10, TimeUnit.MILLISECONDS)) {
                                break;
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            });
        }
    }

    /**
     * 追加线程名到UR参数中中 【注意：线程名中包含URL的地址信息】
     *
     * @return new url with updated thread name
     */
    public static URL setThreadName(URL url, String defaultName) {
        // 从URL中获取 threadname 的值作为线程名，没有就使用defaultName
        String name = url.getParameter(Constants.THREAD_NAME_KEY, defaultName);
        name = name + "-" + url.getAddress();
        url = url.addParameter(Constants.THREAD_NAME_KEY, name);
        return url;
    }
}
