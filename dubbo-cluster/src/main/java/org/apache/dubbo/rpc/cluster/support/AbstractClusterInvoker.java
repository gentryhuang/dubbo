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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_LOADBALANCE;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_AVAILABLE_CHECK_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_STICKY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_CLUSTER_STICKY;

/**
 * AbstractClusterInvoker
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractClusterInvoker.class);

    /**
     * 服务目录
     */
    protected Directory<T> directory;
    /**
     * Invoker 可用性检查。通过 cluster.availablecheck 配置项设置，默认值为 true
     */
    protected boolean availablecheck;
    /**
     * 当前 Cluster Invoker 是否已经销毁
     */
    private AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * 粘滞连接 Invoker
     */
    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker() {
    }

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(CLUSTER_AVAILABLE_CHECK_KEY, DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getConsumerUrl();
    }

    public URL getRegistryUrl() {
        return directory.getUrl();
    }

    /**
     * 判断 Cluster Invoker 是否可用
     * 1. 如果存在粘滞 Invoker，则基于该 Invoker 进行判断
     * 2. 如果不存在粘滞 Invoker，则基于 Directory 判断
     *
     * @return
     */
    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    public Directory<T> getDirectory() {
        return directory;
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a) Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * <p>
     * b) Reselection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * @param loadbalance 负载均衡器
     * @param invocation  调用信息
     * @param invokers    候选的 Invoker 集合
     * @param selected    已选择过的 Invoker 集合
     * @return 目标 Invoker
     * @throws RpcException exception
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        // 1 候选 Invoker 列表为空，直接返回 null
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }

        // 2 获取调用方法名
        String methodName = invocation == null ? StringUtils.EMPTY_STRING : invocation.getMethodName();

        // 3 获取 sticky 配置项，方法级别的
        // sticky 表示粘滞连接，所谓粘滞连接是指 Consumer 会尽可能地调用同一个Provider节点，除非这个Provider无法提供服务
        boolean sticky = invokers.get(0).getUrl()
                .getMethodParameter(methodName, CLUSTER_STICKY_KEY, DEFAULT_CLUSTER_STICKY);

        // 4 检测候选 Invoker 列表是否包含 sticky Invoker。
        // 如果不包含，说明缓存的 sticky Invoker 是不可用的，需要将其置空
        if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
            stickyInvoker = null;
        }

        // 5 开启了粘滞连接特性 & sticky Invoker 存在且没有被选择过 & sticky Invoker 可用
        if (sticky && // 开启粘滞连接特性
                stickyInvoker != null && // sticky Invoker 不为空
                (selected == null || !selected.contains(stickyInvoker))) { // sticky Invoker 未被选择过

            // 检测当前 stickyInvoker 是否可用，如果可用，直接返回 sticky Invoker
            if (availablecheck && stickyInvoker.isAvailable()) {
                return stickyInvoker;
            }
        }

        // 6 sticky Invoker 为空或不可用，则执行选择 Invoker 逻辑
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        // 7 如果开启粘滞连接特性，则更新 stickyInvoker 字段
        if (sticky) {
            stickyInvoker = invoker;
        }

        return invoker;
    }

    /**
     * @param loadbalance 负载均衡对象
     * @param invocation  调用信息
     * @param invokers    候选的 Invoker 列表
     * @param selected    已选过的 Invoker 集合
     * @return
     * @throws RpcException
     */
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        // 1 判断是否需要负载均衡，Invoker 集合为空，则直接返回 null
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }

        // 2 候选 Invoker 仅有一个，则直接返回
        if (invokers.size() == 1) {
            return invokers.get(0);
        }

        // 3 使用负载均衡器选择目标 Invoker
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        // 4 对负载均衡选出的 Invoker 进行校验，决定是否重新选择
        if ((selected != null && selected.contains(invoker)) // 选出的 Invoker 已经被选择过
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) { // 选出的 Invoker 不可用

            try {
                // 4.1 重新进行一次负载均衡
                Invoker<T> rInvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                // 4.2 如果重新选择的 Invoker 对象不为空，则直接使用该 Invoker
                if (rInvoker != null) {
                    invoker = rInvoker;

                    // 4.3 如果重新选择的Invoker为空，就进行容错，无论如何都要选出一个
                } else {

                    // 4.4 第一次选的Invoker如果不是候选Invoker列表中最后一个就选它的下一个，否则就使用候选Invoker列表中的第一个。进行兜底，保证能够获取到一个Invoker
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        invoker = invokers.get((index + 1) % invokers.size());
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`,
     * just pick an available one using loadbalance policy.
     *
     * @param loadbalance    负载均衡器
     * @param invocation     调用信息
     * @param invokers       候选 Invoker 列表
     * @param selected       已选过的 Invoker 列表
     * @param availablecheck 可用性检查
     * @return 目标 Invoker
     * @throws RpcException exception
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck) throws RpcException {

        // 1 预先分配一个列表
        // 注意：这个列表大小比候选的 Invoker列表大小小 1，因为候选Invoker列表中的Invoker可能在selected中或者不可用，从上一步结果可知。
        List<Invoker<T>> reselectInvokers = new ArrayList<>(
                invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        // 2 将不在 selected 集合中且是可用状态的 Invoker 过滤出来参与负载均衡
        for (Invoker<T> invoker : invokers) {
            if (availablecheck && !invoker.isAvailable()) {
                continue;
            }
            if (selected == null || !selected.contains(invoker)) {
                reselectInvokers.add(invoker);
            }
        }

        // 3 reselectInvokers 不为空时，才需要通过负载均衡组件进行选择
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        // 4 线程走到这里，说明 reselectInvokers 集合为空。这时需要兜底，从已经选择过的Invoker列表中选择可用的Invoker列表，然后通过负载均衡器选择一个目标的Invoker
        if (selected != null) {
            for (Invoker<T> invoker : selected) {
                if ((invoker.isAvailable()) // available first
                        && !reselectInvokers.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
        }
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        // 5 实在选不出来，只能返回 null
        return null;
    }

    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        // 1 检查当前 Cluster Invoker 是否已销毁
        checkWhetherDestroyed();

        // 2 将 RpcContext 中的 attachments 添加到 invocation 中
        Map<String, Object> contextAttachments = RpcContext.getContext().getObjectAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addObjectAttachments(contextAttachments);
        }

        // 3 通过 Directory 获取 Invoker 列表
        // 注意，该 Invoker 列表是已经经过 Router 过滤后的结果
        List<Invoker<T>> invokers = list(invocation);

        // 4 通过 SPI 加载 LoadBalance
        // 4.1 如果 Invoker 列表不为空，则根据第一个 Invoker的URL和调用信息初始化
        // 4.2 如果 Invoker 列表为空，则使用默认的负载均衡器
        LoadBalance loadbalance = initLoadBalance(invokers, invocation);

        // 5 如果是异步操作，则添加一个 id 调用编号到 invocation 的 attachment 中
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);

        // 6 执行调用逻辑，由子类实现
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {
        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            throw new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getConsumerUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        return directory.list(invocation);
    }

    /**
     * Init LoadBalance.
     * <p>
     * 1 如果 Invoker 列表不为空，则根据第一个 Invoker的URL和调用信息初始化
     * 2 如果 Invoker 列表为空，则使用默认的负载均衡器
     *
     * </p>
     *
     * @param invokers   invokers
     * @param invocation invocation
     * @return LoadBalance instance. if not need init, return null.
     */
    protected LoadBalance initLoadBalance(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isNotEmpty(invokers)) {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(RpcUtils.getMethodName(invocation), LOADBALANCE_KEY, DEFAULT_LOADBALANCE));
        } else {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(DEFAULT_LOADBALANCE);
        }
    }
}
