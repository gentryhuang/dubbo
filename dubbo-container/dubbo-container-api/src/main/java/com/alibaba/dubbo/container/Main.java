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
package com.alibaba.dubbo.container;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Main. (API, Static, ThreadSafe) 启动器,类似SpringBoot,负责初始化Container 服务容器
 * 说明：
 * 1 Dubbo服务容器只是一个简单Main 方法，默认情况下只会加载一个简单的Spring容器，用于暴露服务。Dubbo服务容器的加载内容可以扩展，即可通过容器扩展点进行扩展，如：spring，log4j等。
 * 2 Dubbo服务容器是Dubbo服务的启动器，它的本质是启动时加载Dubbo的相关内容【通过spring配置，log4j配置等体现】然后启动Dubbo服务。但是 实际生产中，一般不会直接使用Dubbo的服务容器，
 * 更多主流的是使用Spring或者SpringBoot
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);


    /**
     * Container的配置项，如 dubbo.container=spring,log4j
     */
    public static final String CONTAINER_KEY = "dubbo.container";
    /**
     * Dubbo 优雅停机配置项，如 dubbo.shutdown.hook=true
     */
    public static final String SHUTDOWN_HOOK_KEY = "dubbo.shutdown.hook";

    /**
     * Container扩展点的加载器
     */
    private static final ExtensionLoader<Container> loader = ExtensionLoader.getExtensionLoader(Container.class);
    /**
     * 锁
     */
    private static final ReentrantLock LOCK = new ReentrantLock();
    /**
     * 锁的条件
     */
    private static final Condition STOP = LOCK.newCondition();

    /**
     * @param args 启动参数，可以在启动时指定要加载的容器，如 java com.alibaba.dubbo.container.Main spring log4j
     */
    public static void main(String[] args) {
        try {

            // 如果 main 方法的参数没有传入值，则从配置中加载。如果获取不到就使用Container 默认扩展 spring
            if (args == null || args.length == 0) {
                String config = ConfigUtils.getProperty(CONTAINER_KEY, loader.getDefaultExtensionName());
                args = Constants.COMMA_SPLIT_PATTERN.split(config);
            }

            final List<Container> containers = new ArrayList<Container>();
            for (int i = 0; i < args.length; i++) {
                // 使用Dubbo SPI 加载 Container ,并把加载的Container 放入到List中
                containers.add(loader.getExtension(args[i]));
            }


            logger.info("Use container type(" + Arrays.toString(args) + ") to run dubbo serivce.");

            // 当配置JVM启动参数带有 -Ddubbo.shutdown.hook=true时，添加关闭的ShutdownHook
            if ("true".equals(System.getProperty(SHUTDOWN_HOOK_KEY))) {

                Runtime.getRuntime().addShutdownHook(new Thread("dubbo-container-shutdown-hook") {
                    @Override
                    public void run() {
                        for (Container container : containers) {
                            try {
                                // 关闭容器
                                container.stop();
                                logger.info("Dubbo " + container.getClass().getSimpleName() + " stopped!");
                            } catch (Throwable t) {
                                logger.error(t.getMessage(), t);
                            }
                            try {
                                // 获得 ReentrantLock
                                LOCK.lock();
                                // 唤醒 Main 主线程的等待
                                STOP.signal();
                            } finally {
                                // 释放 LOCK
                                LOCK.unlock();
                            }
                        }
                    }
                });
            }

            // 启动容器
            for (Container container : containers) {
                container.start();
                logger.info("Dubbo " + container.getClass().getSimpleName() + " started!");
            }

            System.out.println(new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss]").format(new Date()) + " Dubbo service server started!");

            // 发生异常，打印错误日志，并JVM退出
        } catch (RuntimeException e) {
            e.printStackTrace();
            logger.error(e.getMessage(), e);
            System.exit(1);
        }
        try {
            // 获得 LOCK 锁
            LOCK.lock();
            /**
             * 释放锁，进入等待，直到被唤醒
             * 作用：线程不结束，不触发JVM退出，这样Dubbo就不会退出。如果不等待，main方法执行完成，就会触发JVM退出，导致Dubbo服务退出
             */
            STOP.await();
        } catch (InterruptedException e) {
            logger.warn("Dubbo service server stopped, interrupted by other thread!", e);
        } finally {
            // 释放 LOCK
            LOCK.unlock();
        }
    }

}
