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
package com.alibaba.dubbo.rpc.cluster.directory;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.router.MockInvokersSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Abstract implementation of Directory: Invoker list returned from this Directory's list method have been filtered by Routers
 * <p>
 * Directory抽象实现类，实现了公用的路由规则逻辑
 */
public abstract class AbstractDirectory<T> implements Directory<T> {


    private static final Logger logger = LoggerFactory.getLogger(AbstractDirectory.class);

    /**
     * 注册中心URL
     */
    private final URL url;

    /**
     * 是否已经销毁
     */
    private volatile boolean destroyed = false;

    /**
     * 消费者 URL
     * 注意：如果没有显示嗲用构造方法，那么该属性的值为 url的值
     */
    private volatile URL consumerUrl;

    /**
     * 路由数组
     */
    private volatile List<Router> routers;

    public AbstractDirectory(URL url) {
        this(url, null);
    }

    public AbstractDirectory(URL url, List<Router> routers) {
        this(url, url, routers);
    }

    public AbstractDirectory(URL url, URL consumerUrl, List<Router> routers) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        // 设置url
        this.url = url;
        // 设置consumerUrl
        this.consumerUrl = consumerUrl;
        // 设置路由数组
        setRouters(routers);
    }

    /**
     * 获得调用信息对应的Invoker 集合
     *
     * @param invocation 调用信息
     * @return Invoker 列表
     * @throws RpcException
     */
    @Override
    public List<Invoker<T>> list(Invocation invocation) throws RpcException {

        // 服务目录销毁了就直接抛出异常
        if (destroyed) {
            throw new RpcException("Directory already destroyed .url: " + getUrl());
        }

        // 获取 Invokers 集合
        List<Invoker<T>> invokers = doList(invocation);

        // 使用路由
        List<Router> localRouters = this.routers;

        // 使用路由规则筛选Invoker集合
        if (localRouters != null && !localRouters.isEmpty()) {
            for (Router router : localRouters) {
                try {

                    /**
                     * 根据路由的URL值以及 runtime 参数，决定是否进行路由
                     * 注意：
                     *  Router的runtime参数决定是否每次调用服务时都执行路由规则。如果 runtime配置为true，每次调用服务前都需要进行服务路由，这个会对性能会造成影响。
                     */
                    if (router.getUrl() == null || router.getUrl().getParameter(Constants.RUNTIME_KEY, false)) {
                        // 使用路由筛选Invoker
                        invokers = router.route(invokers, getConsumerUrl(), invocation);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
                }
            }
        }
        return invokers;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    public List<Router> getRouters() {
        return routers;
    }

    /**
     * 设置路由
     * 注意：
     * 在设置路由的时候，会添加一个 MockInvokersSelector，MockInvoker路由选择器。因此在选择目标Invoker的时候，这个路由选择器也会执行
     *
     * @param routers
     */
    protected void setRouters(List<Router> routers) {

        // 复制路由集合，下面逻辑要做修改
        routers = routers == null ? new ArrayList<Router>() : new ArrayList<Router>(routers);

        // 从URL中取出配置的路由
        String routerkey = url.getParameter(Constants.ROUTER_KEY);

        // 如果URL中配置了路由，则获取对应的路由实现，并加入到 routers集合 中
        if (routerkey != null && routerkey.length() > 0) {
            RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class).getExtension(routerkey);
            routers.add(routerFactory.getRouter(url));
        }

        // 添加 MockInvokersSelector
        routers.add(new MockInvokersSelector());

        // 排序
        Collections.sort(routers);

        // 放入缓存
        this.routers = routers;
    }

    public URL getConsumerUrl() {
        return consumerUrl;
    }

    public void setConsumerUrl(URL consumerUrl) {
        this.consumerUrl = consumerUrl;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void destroy() {
        destroyed = true;
    }

    /**
     * 由具体子类列举Invoker列表，模版方法模式
     *
     * @param invocation
     * @return
     * @throws RpcException
     */
    protected abstract List<Invoker<T>> doList(Invocation invocation) throws RpcException;

}
