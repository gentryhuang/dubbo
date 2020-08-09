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
package com.alibaba.dubbo.container.logback;

import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.container.Container;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import org.slf4j.LoggerFactory;

/**
 * LogbackContainer. (SPI, Singleton, ThreadSafe)
 * 实现 Container 接口，Logback 容器实现类
 */
public class LogbackContainer implements Container {

    /**
     * 日志文件路径
     */
    public static final String LOGBACK_FILE = "dubbo.logback.file";
    /**
     * 日志文件级别
     */
    public static final String LOGBACK_LEVEL = "dubbo.logback.level";
    /**
     * 日志保留天数
     */
    public static final String LOGBACK_MAX_HISTORY = "dubbo.logback.maxhistory";

    /**
     * 默认日志级别
     */
    public static final String DEFAULT_LOGBACK_LEVEL = "ERROR";

    @Override
    public void start() {
        // 获得 logback 配置的日志文件路径
        String file = ConfigUtils.getProperty(LOGBACK_FILE);

        if (file != null && file.length() > 0) {
            // 获得日志级别
            String level = ConfigUtils.getProperty(LOGBACK_LEVEL);
            if (level == null || level.length() == 0) {
                level = DEFAULT_LOGBACK_LEVEL;
            }

            // 获得日志保留天数，如果是0则永久保留
            int maxHistory = StringUtils.parseInteger(ConfigUtils.getProperty(LOGBACK_MAX_HISTORY));

            // 初始化 logback
            doInitializer(file, level, maxHistory);
        }
    }

    /**
     * 停止为空，因为不需要关闭
     */
    @Override
    public void stop() {
    }

    /**
     * 初始化 logback
     *
     * @param file       日志文件路径
     * @param level      日志级别
     * @param maxHistory 日志保留天数
     */
    private void doInitializer(String file, String level, int maxHistory) {
        // 获取日志工厂
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        // 通过工厂获取Logger
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.detachAndStopAllAppenders();

        // 创建日志追加器
        RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<ILoggingEvent>();
        fileAppender.setContext(loggerContext);
        fileAppender.setName("application");
        fileAppender.setFile(file);
        fileAppender.setAppend(true);

        // 创建滚动策略
        TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<ILoggingEvent>();
        policy.setContext(loggerContext);
        policy.setMaxHistory(maxHistory);
        policy.setFileNamePattern(file + ".%d{yyyy-MM-dd}");
        policy.setParent(fileAppender);
        policy.start();
        fileAppender.setRollingPolicy(policy);

        // 格式
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%date [%thread] %-5level %logger (%file:%line\\) - %msg%n");
        encoder.start();
        fileAppender.setEncoder(encoder);

        fileAppender.start();

        rootLogger.addAppender(fileAppender);
        rootLogger.setLevel(Level.toLevel(level));
        rootLogger.setAdditive(false);
    }

}
