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
package com.alibaba.dubbo.monitor.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.monitor.Monitor;
import com.alibaba.dubbo.monitor.MonitorFactory;
import com.alibaba.dubbo.monitor.MonitorService;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MonitorFilter. (SPI, Singleton, ThreadSafe) 收集监控数据
 * 说明：
 * 消费端在发起调用之前会先执行该Filter，服务端端在接收到请求时也是先执行该Filter，然后才进行真正的业务逻辑处理。默认情况下，消费者和提供者的Filter链中都会有 MonitorFilter
 */
@Activate(group = {Constants.PROVIDER, Constants.CONSUMER})
public class MonitorFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(MonitorFilter.class);

    /**
     * 完整的方法名到并发数的映射
     * key: 接口名+方法名
     * value: 并发数
     */
    private final ConcurrentMap<String, AtomicInteger> concurrents = new ConcurrentHashMap<String, AtomicInteger>();

    /**
     * 监控工厂，用于创建Monitor
     */
    private MonitorFactory monitorFactory;

    public void setMonitorFactory(MonitorFactory monitorFactory) {
        this.monitorFactory = monitorFactory;
    }

    /**
     * 收集调用数据
     *
     * @param invoker    service
     * @param invocation invocation.
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {

        // 读取配置参数monitor，看是否开启monitor监控
        if (invoker.getUrl().hasParameter(Constants.MONITOR_KEY)) {

            // 获取Dubbo上下文
            RpcContext context = RpcContext.getContext(); // provider must fetch context before invoke() gets called
            // 获取远程地址
            String remoteHost = context.getRemoteHost();

            // 记录开始时间
            long start = System.currentTimeMillis();

            // 递增并发数
            getConcurrent(invoker, invocation).incrementAndGet();

            try {

                // RPC调用
                Result result = invoker.invoke(invocation);

                // 收集统计数
                collect(invoker, invocation, result, remoteHost, start, false);

                return result;
            } catch (RpcException e) {

                // 发生异常也统计数据
                collect(invoker, invocation, null, remoteHost, start, true);
                throw e;

            } finally {

                // 递减并发数
                getConcurrent(invoker, invocation).decrementAndGet();
            }

            // 没有开启监控，直接放行
        } else {
            return invoker.invoke(invocation);
        }
    }

    /**
     * 统计数据
     *
     * @param invoker    invoker
     * @param invocation 调用信息
     * @param result     调用结果 【可能为空】
     * @param remoteHost 远程地址
     * @param start      调用开始时间
     * @param error      是否异常
     */
    private void collect(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        try {

            // 调用话费时间
            long elapsed = System.currentTimeMillis() - start;
            // 当前并发数
            int concurrent = getConcurrent(invoker, invocation).get();
            // 获取应用名
            String application = invoker.getUrl().getParameter(Constants.APPLICATION_KEY);
            // 接口名
            String service = invoker.getInterface().getName();
            // 方法名
            String method = RpcUtils.getMethodName(invocation);
            // 组
            String group = invoker.getUrl().getParameter(Constants.GROUP_KEY);
            // 版本
            String version = invoker.getUrl().getParameter(Constants.VERSION_KEY);
            // monitorUrl
            URL url = invoker.getUrl().getUrlParameter(Constants.MONITOR_KEY);
            // 通过监控工厂，根据monitorUrl创建Monitor对象
            Monitor monitor = monitorFactory.getMonitor(url);

            if (monitor == null) {
                return;
            }

            int localPort;
            String remoteKey;
            String remoteValue;

            // 如果是消费者
            if (Constants.CONSUMER_SIDE.equals(invoker.getUrl().getParameter(Constants.SIDE_KEY))) {
                // ---- for service consumer ----
                localPort = 0;
                remoteKey = MonitorService.PROVIDER;
                remoteValue = invoker.getUrl().getAddress();

                // 如果是服务提供者
            } else {
                // ---- for service provider ----
                localPort = invoker.getUrl().getPort();
                remoteKey = MonitorService.CONSUMER;
                remoteValue = remoteHost;
            }


            String input = "", output = "";
            if (invocation.getAttachment(Constants.INPUT_KEY) != null) {
                input = invocation.getAttachment(Constants.INPUT_KEY);
            }
            if (result != null && result.getAttachment(Constants.OUTPUT_KEY) != null) {
                output = result.getAttachment(Constants.OUTPUT_KEY);
            }

            // 使用DubboMonitor收集数据
            monitor.collect(
                    // 将统计数据构建成URL
                    new URL(
                            Constants.COUNT_PROTOCOL,
                            NetUtils.getLocalHost(),
                            localPort,
                            service + "/" + method,
                            MonitorService.APPLICATION,
                            application,
                            MonitorService.INTERFACE,
                            service,
                            MonitorService.METHOD,
                            method,
                            remoteKey,
                            remoteValue,
                            error ? MonitorService.FAILURE : MonitorService.SUCCESS,
                            "1",
                            MonitorService.ELAPSED,
                            String.valueOf(elapsed),
                            MonitorService.CONCURRENT,
                            String.valueOf(concurrent),
                            Constants.INPUT_KEY, input,
                            Constants.OUTPUT_KEY, output,
                            Constants.GROUP_KEY, group,
                            Constants.VERSION_KEY, version
                    )
            );


        } catch (Throwable t) {
            logger.error("Failed to monitor count service " + invoker.getUrl() + ", cause: " + t.getMessage(), t);
        }
    }

    /**
     * 获取并发数
     *
     * @param invoker
     * @param invocation
     * @return
     */
    private AtomicInteger getConcurrent(Invoker<?> invoker, Invocation invocation) {
        String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
        AtomicInteger concurrent = concurrents.get(key);
        if (concurrent == null) {
            concurrents.putIfAbsent(key, new AtomicInteger());
            concurrent = concurrents.get(key);
        }
        return concurrent;
    }

}
