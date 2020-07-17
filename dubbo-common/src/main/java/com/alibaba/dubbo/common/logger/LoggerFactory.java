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
package com.alibaba.dubbo.common.logger;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.jcl.JclLoggerAdapter;
import com.alibaba.dubbo.common.logger.jdk.JdkLoggerAdapter;
import com.alibaba.dubbo.common.logger.log4j.Log4jLoggerAdapter;
import com.alibaba.dubbo.common.logger.slf4j.Slf4jLoggerAdapter;
import com.alibaba.dubbo.common.logger.support.FailsafeLogger;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Logger factory，Logger的工厂类
 * 说明：
 * 1 Logger Factory 通过 日志适配器 LoggerApapter去创建 具体的Logger,即过程：LoggerFactory -> Log4jLoggerAdapter -> Logger
 * 2 一般情况下，我们使用 slf4j ，并使用 slf4j 对应的适配库(默认是logback，因为logback实现了slf4j)。如果需要适配其他的日志组件，可以添加对应的依赖包。
 */
public class LoggerFactory {

    /**
     * 已创建的Logger 对应的映射
     * key: 类名
     * value: Logger
     */
    private static final ConcurrentMap<String, FailsafeLogger> LOGGERS = new ConcurrentHashMap<String, FailsafeLogger>();

    /**
     * 当前使用的LoggerAdapter 日志适配器
     */
    private static volatile LoggerAdapter LOGGER_ADAPTER;

    /**
     * 根据 logger 配置项，调用setLoggerAdapter(LoggerAdapter)方法，进行设置 LOGGER_ADAPTER属性
     */
    static {

        // 获得 logger 配置项
        String logger = System.getProperty("dubbo.application.logger");

        // 根据配置项，创建对应的 LoggerAdapter 对象
        if ("slf4j".equals(logger)) {
            setLoggerAdapter(new Slf4jLoggerAdapter());
        } else if ("jcl".equals(logger)) {
            setLoggerAdapter(new JclLoggerAdapter());
        } else if ("log4j".equals(logger)) {
            setLoggerAdapter(new Log4jLoggerAdapter());
        } else if ("jdk".equals(logger)) {
            setLoggerAdapter(new JdkLoggerAdapter());

            // 如果没有配置，就按照 Log4jLoggerAdapter -> Slf4jLoggerAdapter -> JclLoggerAdapter -> JclLoggerAdapter 顺序创建
        } else {
            try {
                setLoggerAdapter(new Log4jLoggerAdapter());
            } catch (Throwable e1) {
                try {
                    setLoggerAdapter(new Slf4jLoggerAdapter());
                } catch (Throwable e2) {
                    try {
                        setLoggerAdapter(new JclLoggerAdapter());
                    } catch (Throwable e3) {
                        setLoggerAdapter(new JclLoggerAdapter());
                    }
                }
            }
        }
    }

    private LoggerFactory() {
    }

    /**
     * 设置 LoggerAdapter
     * 说明：
     * 根据扩展名获得对应的LoggerAdapter 扩展实现类,如 {@link com.alibaba.dubbo.config.ApplicationConfig#setLogger(java.lang.String)}
     *
     * @param loggerAdapter
     */
    public static void setLoggerAdapter(String loggerAdapter) {
        if (loggerAdapter != null && loggerAdapter.length() > 0) {
            // SPI机制根据扩展名获得对应的 LoggerAdapter，并设置LoggerAdapter
            setLoggerAdapter(ExtensionLoader.getExtensionLoader(LoggerAdapter.class).getExtension(loggerAdapter));
        }
    }

    /**
     * Set logger provider
     *
     * @param loggerAdapter logger provider
     */
    public static void setLoggerAdapter(LoggerAdapter loggerAdapter) {
        if (loggerAdapter != null) {
            // 获得 Logger 对象，并打印日志
            Logger logger = loggerAdapter.getLogger(LoggerFactory.class.getName());
            logger.info("using logger: " + loggerAdapter.getClass().getName());
            // 设置日志适配器
            LoggerFactory.LOGGER_ADAPTER = loggerAdapter;

            // 循环Logger的缓存映射集合，全部替换为新的
            for (Map.Entry<String, FailsafeLogger> entry : LOGGERS.entrySet()) {
                entry.getValue().setLogger(LOGGER_ADAPTER.getLogger(entry.getKey()));
            }
        }
    }

    /**
     * 获取Logger，优先从 LOGGER 缓存中取，不存在则会根据LoggerAdapter适配器创建一个新的并进行缓存
     *
     * @param key the returned logger will be named after clazz
     * @return logger
     */
    public static Logger getLogger(Class<?> key) {
        // 从缓存中，获得 Logger 对象
        FailsafeLogger logger = LOGGERS.get(key.getName());
        // 不存在，则根据日志适配器创建信息的并缓存起来【注意：会使用FailsafeLogger进行包裹】
        if (logger == null) {
            LOGGERS.putIfAbsent(key.getName(), new FailsafeLogger(LOGGER_ADAPTER.getLogger(key)));
            logger = LOGGERS.get(key.getName());
        }
        return logger;
    }

    /**
     * 获取Logger，getLogger(Class<?> key) 方法基本一样
     *
     * @param key the returned logger will be named after key
     * @return logger provider
     */
    public static Logger getLogger(String key) {
        FailsafeLogger logger = LOGGERS.get(key);
        if (logger == null) {
            LOGGERS.putIfAbsent(key, new FailsafeLogger(LOGGER_ADAPTER.getLogger(key)));
            logger = LOGGERS.get(key);
        }
        return logger;
    }

    /**
     * 获取当前日志级别
     *
     * @return logging level
     */
    public static Level getLevel() {
        return LOGGER_ADAPTER.getLevel();
    }

    /**
     * 设置当前日志级别
     *
     * @param level logging level
     */
    public static void setLevel(Level level) {
        LOGGER_ADAPTER.setLevel(level);
    }

    /**
     * 获取当前日志文件
     *
     * @return current logging file
     */
    public static File getFile() {
        return LOGGER_ADAPTER.getFile();
    }

}