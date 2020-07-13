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
package com.alibaba.dubbo.container.spring;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.container.Container;

import org.apache.dubbo.config.spring.initializer.DubboApplicationListener;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * SpringContainer. (SPI, Singleton, ThreadSafe)
 * 实现Container接口，Spring容器实现类
 */
public class SpringContainer implements Container {

    private static final Logger logger = LoggerFactory.getLogger(SpringContainer.class);

    /**
     * Spring 配置属性
     */
    public static final String SPRING_CONFIG = "dubbo.spring.config";
    /**
     * 默认配置文件地址
     */
    public static final String DEFAULT_SPRING_CONFIG = "classpath*:META-INF/spring/*.xml";

    /**
     * Spring 上线文
     * 静态属性，全局唯一
     */
    static ClassPathXmlApplicationContext context;

    public static ClassPathXmlApplicationContext getContext() {
        return context;
    }

    @Override
    public void start() {

        // 获得Spring 配置文件的地址【先优先从JVM参数中取，没有再从dubbo.properties文件中取】
        String configPath = ConfigUtils.getProperty(SPRING_CONFIG);

        // 如果没有配置就使用默认路径下的配置文件
        if (configPath == null || configPath.length() == 0) {
            configPath = DEFAULT_SPRING_CONFIG;
        }

        // 创建Spring 上下文
        context = new ClassPathXmlApplicationContext(configPath.split("[,\\s]+"), false);

        // 添加监听器
        context.addApplicationListener(new DubboApplicationListener());

        // 监听容器关闭
        context.registerShutdownHook();

        // 刷新Spring容器
        context.refresh();
        // 启动Spring容器，加载Dubbo的配置，从而启动Dubbo 服务
        context.start();
    }

    @Override
    public void stop() {
        try {
            if (context != null) {
                // 停止上下文，会触发 ContextStoppedEvent 事件
                context.stop();
                // 关闭上下文，会触发 ContextClosedEvent 事件
                context.close();

                // 置空，便于被回收
                context = null;
            }
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
        }
    }

}
