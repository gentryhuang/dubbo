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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.fastjson.JSON;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Record access log for the service.
 * <p>
 * Logger key is <code><b>dubbo.accesslog</b></code>.
 * In order to configure access log appear in the specified appender only, additivity need to be configured in log4j's
 * config file, for example:
 * <code>
 * <pre>
 * &lt;logger name="<b>dubbo.accesslog</b>" <font color="red">additivity="false"</font>&gt;
 *    &lt;level value="info" /&gt;
 *    &lt;appender-ref ref="foo" /&gt;
 * &lt;/logger&gt;
 * </pre></code>
 * <p>
 * 记录服务的访问日志的过滤器实现类
 * <p>
 * 说明：配置Dubbo 的 日志项，可以在 <dubbo:protocol/> 或 <dubbo:provider/> 或 <dubbo:service /> 中，设置 accesslog 配置项，用于开启日志：
 * 1 设置为 true ： 将向日志组件Logger 中输出访问日志
 * 2 设置一个路径 ： 直接把访问日志输出到指定文件
 * 注意：
 * 如果把日志输入到文件的情况，会有两个问题：1 由于Set集合是无序的，因此日志输出到文件也是无序的 2 由于是异步刷盘，如果服务突然宕机会导致一部分日志丢失
 */
@Activate(group = Constants.PROVIDER, value = Constants.ACCESS_LOG_KEY)
public class AccessLogFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AccessLogFilter.class);


    //--------------- 使用日志组件输出相关属性 -------------------------/

    /**
     * 日志名前缀，用于获取日志组件。用于 accesslog = true，或 accesslog = default 的情况
     */
    private static final String ACCESS_LOG_KEY = "dubbo.accesslog";


    //--------------- 配置输出到指定文件的相关属性 ---------------------/
    /**
     * 日志的文件后缀
     */
    private static final String FILE_DATE_FORMAT = "yyyyMMdd";
    /**
     * 时间格式化
     */
    private static final String MESSAGE_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    /**
     * 队列大小
     */
    private static final int LOG_MAX_BUFFER = 5000;
    /**
     * 日志输出频率，单位：毫秒
     */
    private static final long LOG_OUTPUT_INTERVAL = 5000;

    /**
     * 日志队列
     * key: 自定的 accesslog 的值，如： accesslog="accesslog.log"
     * value: 日志集合
     */
    private final ConcurrentMap<String, Set<String>> logQueue = new ConcurrentHashMap<String, Set<String>>();
    /**
     * 定时任务线程池
     */
    private final ScheduledExecutorService logScheduled = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Dubbo-Access-Log", true));
    /**
     * 记录日志任务
     */
    private volatile ScheduledFuture<?> logFuture = null;

    /**
     * 初始化任务
     */
    private void init() {
        // 双重检锁机制，防止重复初始化
        if (logFuture == null) {
            synchronized (logScheduled) {
                if (logFuture == null) {
                    logFuture = logScheduled.scheduleWithFixedDelay(new LogTask(), LOG_OUTPUT_INTERVAL, LOG_OUTPUT_INTERVAL, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /**
     * 添加日志内容到日志队列
     *
     * @param accesslog  日志路径
     * @param logmessage 日志内容
     */
    private void log(String accesslog, String logmessage) {
        // 初始化任务
        init();

        // 获得日志队列
        Set<String> logSet = logQueue.get(accesslog);
        if (logSet == null) {
            logQueue.putIfAbsent(accesslog, new ConcurrentHashSet<String>());
            logSet = logQueue.get(accesslog);
        }

        // 若未超过队列大小，添加到队列中
        if (logSet.size() < LOG_MAX_BUFFER) {
            logSet.add(logmessage);
        }
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        try {
            // 记录访问日志的文件名
            String accesslog = invoker.getUrl().getParameter(Constants.ACCESS_LOG_KEY);
            if (ConfigUtils.isNotEmpty(accesslog)) {
                // dubbo 上下文
                RpcContext context = RpcContext.getContext();
                // 服务名
                String serviceName = invoker.getInterface().getName();
                // 版本号
                String version = invoker.getUrl().getParameter(Constants.VERSION_KEY);
                // 分组
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY);
                // 拼接日志内容
                StringBuilder sn = new StringBuilder();
                sn.append("[")
                        // 时间
                        .append(new SimpleDateFormat(MESSAGE_DATE_FORMAT).format(new Date()))
                        // 调用方地址
                        .append("] ").append(context.getRemoteHost()).append(":").append(context.getRemotePort())
                        // 本地地址
                        .append(" -> ").append(context.getLocalHost()).append(":").append(context.getLocalPort())
                        .append(" - ");

                // 分组
                if (null != group && group.length() > 0) {
                    sn.append(group).append("/");
                }
                // 服务名
                sn.append(serviceName);
                // 版本
                if (null != version && version.length() > 0) {
                    sn.append(":").append(version);
                }
                sn.append(" ");
                // 方法名
                sn.append(inv.getMethodName());
                sn.append("(");
                // 参数类型
                Class<?>[] types = inv.getParameterTypes();
                if (types != null && types.length > 0) {
                    boolean first = true;
                    for (Class<?> type : types) {
                        if (first) {
                            first = false;
                        } else {
                            sn.append(",");
                        }
                        sn.append(type.getName());
                    }
                }
                sn.append(") ");
                // 参数值
                Object[] args = inv.getArguments();
                if (args != null && args.length > 0) {
                    sn.append(JSON.toJSONString(args));
                }
                // 日志信息字符串
                String msg = sn.toString();
                // 设置 accesslog = true 或 accesslog=default，将日志输出到日志组件Logger，如 logback中
                if (ConfigUtils.isDefault(accesslog)) {
                    // 写日志
                    LoggerFactory.getLogger(ACCESS_LOG_KEY + "." + invoker.getInterface().getName()).info(msg);
                } else {
                    // 异步输出到指定文件
                    log(accesslog, msg);
                }
            }
        } catch (Throwable t) {
            logger.warn("Exception in AcessLogFilter of service(" + invoker + " -> " + inv + ")", t);
        }
        return invoker.invoke(inv);
    }

    /**
     * 日志任务
     */
    private class LogTask implements Runnable {
        @Override
        public void run() {
            try {
                if (logQueue != null && logQueue.size() > 0) {
                    // 遍历日志队列
                    for (Map.Entry<String, Set<String>> entry : logQueue.entrySet()) {
                        try {
                            // 获得日志文件路径
                            String accesslog = entry.getKey();
                            // 获得日志集合
                            Set<String> logSet = entry.getValue();
                            // 创建日志文件
                            File file = new File(accesslog);
                            File dir = file.getParentFile();
                            if (null != dir && !dir.exists()) {
                                dir.mkdirs();
                            }
                            if (logger.isDebugEnabled()) {
                                logger.debug("Append log to " + accesslog);
                            }
                            // 归档历史日志文件，例如：  xxx.20191217
                            if (file.exists()) {
                                String now = new SimpleDateFormat(FILE_DATE_FORMAT).format(new Date());
                                String last = new SimpleDateFormat(FILE_DATE_FORMAT).format(new Date(file.lastModified()));
                                if (!now.equals(last)) {
                                    File archive = new File(file.getAbsolutePath() + "." + last);
                                    file.renameTo(archive);
                                }
                            }

                            // 输出日志到指定文件
                            FileWriter writer = new FileWriter(file, true);
                            try {
                                for (Iterator<String> iterator = logSet.iterator();
                                     iterator.hasNext();
                                     iterator.remove()) {

                                    // 写入一行日志
                                    writer.write(iterator.next());
                                    // 换行
                                    writer.write("\r\n");
                                }
                                // 刷盘
                                writer.flush();
                            } finally {
                                writer.close();
                            }
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

}
