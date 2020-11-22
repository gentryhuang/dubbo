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
package com.alibaba.dubbo.rpc.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * abstract ProtocolSupport.
 * <p>
 * 提供了一些 Protocol 实现需要的公共能力以及公共字段
 * 1 存储服务暴露的 Map
 * 2 存储服务引用的 Set
 * 3 销毁
 * 4 服务键方法（Injvm 所需服务键非这里的方法）
 */
public abstract class AbstractProtocol implements Protocol {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Exporter集合，用于存储暴露出去的服务集合。注意，包括 injvm 协议暴露出去的
     * 1 key: 服务键，{@link #serviceKey(URL)} 或者 {@link URL#getServiceKey()},不同协议会有所差别：
     * （1）InjvmProtocol使用URL#getServicekey()方法
     * （2）DubboProtocol使用#serviceKey(URL)方法
     * 2 该集合拥有当前协议中所有暴露中的Exporter对象
     */
    protected final Map<String, Exporter<?>> exporterMap = new ConcurrentHashMap<String, Exporter<?>>();

    /**
     * 服务引用集合
     */
    protected final Set<Invoker<?>> invokers = new ConcurrentHashSet<Invoker<?>>();

    protected static String serviceKey(URL url) {
        // 绑定端口
        int port = url.getParameter(Constants.BIND_PORT_KEY, url.getPort());


        return serviceKey(
                port, // 端口
                url.getPath(),// 服务接口全路径名
                url.getParameter(Constants.VERSION_KEY), // 版本
                url.getParameter(Constants.GROUP_KEY)); // 组名
    }

    /**
     * 获取服务键
     *
     * @param port
     * @param serviceName
     * @param serviceVersion
     * @param serviceGroup
     * @return
     */
    protected static String serviceKey(int port, String serviceName, String serviceVersion, String serviceGroup) {
        return ProtocolUtils.serviceKey(port, serviceName, serviceVersion, serviceGroup);
    }


    /**
     * 1 销毁全部的服务引用
     * 2 销毁发布出去的服务
     */
    @Override
    public void destroy() {

        // 销毁协议对应的服务消费者的所有 Invoker，如： 如果是Dubbo协议，那么这里的服务消费者的所有Invoker则为DubboInvoker
        for (Invoker<?> invoker : invokers) {
            if (invoker != null) {
                invokers.remove(invoker);
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Destroy reference: " + invoker.getUrl());
                    }
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        // 销毁协议对应的服务提供者的所有Exporter，如：如果是Dubbo协议，那么这里的服务提供者的所有Exporter则为DubboExporter
        for (String key : new ArrayList<String>(exporterMap.keySet())) {
            Exporter<?> exporter = exporterMap.remove(key);
            if (exporter != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Unexport service: " + exporter.getInvoker().getUrl());
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }
}
