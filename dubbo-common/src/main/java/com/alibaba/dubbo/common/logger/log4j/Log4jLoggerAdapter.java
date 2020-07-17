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
package com.alibaba.dubbo.common.logger.log4j;

import com.alibaba.dubbo.common.logger.Level;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerAdapter;

import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.LogManager;

import java.io.File;
import java.util.Enumeration;

/**
 * Log4j的日志适配器
 */
public class Log4jLoggerAdapter implements LoggerAdapter {

    /**
     * Root Logger 文件
     */
    private File file;

    @SuppressWarnings("unchecked")
    public Log4jLoggerAdapter() {
        try {
            // 获得 Root Logger 对象
            org.apache.log4j.Logger logger = LogManager.getRootLogger();
            if (logger != null) {

                // 获取Logger 对象的 追加器
                Enumeration<Appender> appenders = logger.getAllAppenders();

                if (appenders != null) {
                    while (appenders.hasMoreElements()) {
                        Appender appender = appenders.nextElement();
                        // 是FileAppender时，会创建File
                        if (appender instanceof FileAppender) {
                            FileAppender fileAppender = (FileAppender) appender;
                            String filename = fileAppender.getFile();
                            file = new File(filename);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
        }
    }

    /**
     * 将 Dubbo 的日志级别转成 Log4j 的日志级别
     *
     * @param level
     * @return
     */
    private static org.apache.log4j.Level toLog4jLevel(Level level) {
        if (level == Level.ALL) {
            return org.apache.log4j.Level.ALL;
        }
        if (level == Level.TRACE) {
            return org.apache.log4j.Level.TRACE;
        }
        if (level == Level.DEBUG) {
            return org.apache.log4j.Level.DEBUG;
        }
        if (level == Level.INFO) {
            return org.apache.log4j.Level.INFO;
        }
        if (level == Level.WARN) {
            return org.apache.log4j.Level.WARN;
        }
        if (level == Level.ERROR) {
            return org.apache.log4j.Level.ERROR;
        }
        // if (level == Level.OFF)
        return org.apache.log4j.Level.OFF;
    }

    /**
     * 将 Log4j 的日志级别转成 Dubbo实现的日志级别
     *
     * @param level
     * @return
     */
    private static Level fromLog4jLevel(org.apache.log4j.Level level) {
        if (level == org.apache.log4j.Level.ALL) {
            return Level.ALL;
        }
        if (level == org.apache.log4j.Level.TRACE) {
            return Level.TRACE;
        }
        if (level == org.apache.log4j.Level.DEBUG) {
            return Level.DEBUG;
        }
        if (level == org.apache.log4j.Level.INFO) {
            return Level.INFO;
        }
        if (level == org.apache.log4j.Level.WARN) {
            return Level.WARN;
        }
        if (level == org.apache.log4j.Level.ERROR) {
            return Level.ERROR;
        }
        // if (level == org.apache.log4j.Level.OFF)
        return Level.OFF;
    }

    /**
     * 获取Dubbo 的 Logger对象，内部封装了org.apache.log4j.Logger 对象
     *
     * @param key the returned logger will be named after clazz
     * @return
     */
    @Override
    public Logger getLogger(Class<?> key) {
        // 调用 LogManager#getLogger方法，获取 org.apache.log4j.Logger对象，然后封装到Dubbo的Log4jLogger对象中
        return new Log4jLogger(LogManager.getLogger(key));
    }

    /**
     * 同 {@link Log4jLoggerAdapter#getLogger(java.lang.Class)} 方法作用
     *
     * @param key the returned logger will be named after key
     * @return
     */
    @Override
    public Logger getLogger(String key) {
        return new Log4jLogger(LogManager.getLogger(key));
    }

    /**
     * 获取 RootLogger 日志级别
     *
     * @return
     */
    @Override
    public Level getLevel() {
        return fromLog4jLevel(LogManager.getRootLogger().getLevel());
    }

    /**
     * 设置 Root Logger 的日志级别
     *
     * @param level logging level
     */
    @Override
    public void setLevel(Level level) {
        LogManager.getRootLogger().setLevel(toLog4jLevel(level));
    }

    /**
     * 获取日志文件
     *
     * @return
     */
    @Override
    public File getFile() {
        return file;
    }

    /**
     * 暂不支持
     *
     * @param file logging file
     */
    @Override
    public void setFile(File file) {

    }

}
