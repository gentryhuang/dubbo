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
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractClusterInvoker，ClusterInvoker 抽象类
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractClusterInvoker.class);

    /**
     * 重要- Directory[RegistryDirectory]，通过它，可以获得所有服务提供者的Invoker对象
     */
    protected final Directory<T> directory;
    /**
     * 集群时是否排除非可用的Invoker，默认为true。通过 cluster.availablecheck 配置项设置
     */
    protected final boolean availablecheck;
    /**
     * 是否已经销毁
     */
    private AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * 粘滞连接 Invoker
     * 说明：
     * 粘滞连接用于有状态服务，尽可能让客户端总是向同一提供者发起调用，除非该提供者挂了，再连另一台。粘滞连接将自动开启延迟连接，以减少长连接数。
     * 作用是在调用Invoker的重试过程中，尽可能调用前面选择的Invoker。
     * 配置：
     * <dubbo:reference stick="true"/> 或 <dubbo:reference><dubbo:method name="" sticky="true"></dubbo:method></dubbo:reference>
     * 延迟连接：
     * 延迟连接用于减少长连接数。当有调用发起时，再创建长连接。<dubbo:protocol name="dubbo" lazy="true" />，该配置只对使用长连接的Dubbo协议生效
     */
    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    /**
     * 是否可用
     *
     * @return
     */
    @Override
    public boolean isAvailable() {
        // 如果存在 粘滞 Invoker ，则基于该Invoker 判断
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }

        // 基于 Directory 判断
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * 从候选的Invoker 集合中，选择一个最终调用的Invoker 对象。
     * 先处理粘滞连接的特性，如果存在可用的粘滞Invoker 就直接使用，否则就需要使用负载均衡器进行选择。具体使用LoadBalance选择Invoker 对象的逻辑在 doSelect 方法中
     * <p>
     * Select a invoker using loadbalance policy.</br>
     * a)Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * b)Reslection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * @param loadbalance Loadbalance对象，提供负载均衡策略
     * @param invocation  调用信息
     * @param invokers    invoker candidates  候选的Invoker 集合，即存活的提供者列表
     * @param selected    exclude selected invokers or not  已选过的Invoker集合，注意是已经被选出来的Invoker,failover/forking 的该方法会传入这个值
     * @return 最终的 Invoker 对象
     * @throws RpcException
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.isEmpty()) {
            return null;
        }

        // 获取调用方法名
        String methodName = invocation == null ? "" : invocation.getMethodName();

        // 获得 sticky 配置项，方法级别的。【sticky 表示粘滞连接。所谓粘滞连接是指让服务消费者尽可能的调用同一个服务提供者，除非该提供者挂了再进行切换】
        boolean sticky = invokers.get(0).getUrl().getMethodParameter(methodName, Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY);

        {
            // 如果 stickyInvoker 不存在于候选的invokers 中，说明stickyInvoker对应的提供者可能不可用了，需要置空，重新选择。
            //ignore overloaded method
            if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
                stickyInvoker = null;
            }

            /**
             * 若开启粘滞连接的特性，且stickyInvoker != null。且 stickyInvoker 不存在于 selected 中，则返回 stickyInvoker 这个 Invoker 对象。
             * 注意：
             *  1 selected 中的Invoker，目前都是调用失败的Invoker
             *  2 如果stickyInvoker在 selected 中，说明 stickyInvoker对应的提供者可能是无效的，但是该提供者仍在候选Invoker集合中，不应该在重试期间内再次被调用，因此这个时候不会返回该 stickyInvoker
             */
            //ignore concurrency problem 忽略并发问题
            if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {

                // 若开启排除非可用的 Invoker 的特性，则校验 stickyInvoker 是否可用。若可用才会进行最后的返回
                if (availablecheck && stickyInvoker.isAvailable()) {
                    return stickyInvoker;
                }
            }
        }

        // stickyInvoker为空或不可用，就执行选择Invoker逻辑
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        // 如果开启粘滞连接的特性，记录最终选择的 Invoker 到 stickyInvoker
        if (sticky) {
            stickyInvoker = invoker;
        }
        return invoker;
    }

    /**
     * 从候选的Invoker集合中选择一个Invoker对象
     * 流程：
     * 1 先通过负载均衡器选择一个Invoker
     * 2 如果负载均衡选择的Invoker不稳定或不可用，进行重新选择
     * 3 重新选择没有结果的话，就需要兜底处理
     *
     * @param loadbalance 负载均衡对象
     * @param invocation  调用信息
     * @param invokers    候选的Invoker 列表
     * @param selected    已选过的Invoker集合，注意是已经被选出来的Invoker,failover/forking 的该方法会传入这个值
     * @return 最终选出的Invoker
     * @throws RpcException
     */
    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        // 候选列表为空，直接返回null
        if (invokers == null || invokers.isEmpty()) {
            return null;
        }

        // 1 如果候选的Invoker列表仅仅有一个Invoker，就直接返回
        if (invokers.size() == 1) {
            return invokers.get(0);
        }

        // 如果负载均衡器为空，就使用默认的 random
        if (loadbalance == null) {
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }

        // 2 使用负载均衡器选择目标 Invoker
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        // 如果 selected 中包含负载均衡器选中的Invoker，或则 负载均衡器选中的Invoker不可以用 && availablecheck，则重新选择
        if (
                (selected != null && selected.contains(invoker)) ||
                        (!invoker.isAvailable() && getUrl() != null && availablecheck)
        ) {
            try {
                // 3 重新选择一个目标Invoker
                Invoker<T> rinvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);

                // 重新选择的Invoker非空就使用它作为目标Invoker
                if (rinvoker != null) {
                    invoker = rinvoker;

                    // 重新选择的Invoker为空，就进行容错，无论如何都要选出一个
                } else {

                    // 确定第一次选择的Invoker 在 候选Invoker列表中的位置
                    int index = invokers.indexOf(invoker);
                    try {
                        /**
                         * 4 第一次选的Invoker如果不是候选Invoker列表中最后一个就选它的下一个，否则就使用候选Invoker列表中的第一个。进行兜底，保证能够获取到一个Invoker
                         *   即获取候选列表的 index + 1 下标 对应的Invoker
                         */
                        invoker = index < invokers.size() - 1 ? invokers.get(index + 1) : invokers.get(0);
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
     * 重新选择一个Invoker 对象
     * <p>
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`, just pick an available one using loadbalance policy.
     *
     * @param loadbalance    负载均衡对象
     * @param invocation     调用信息
     * @param invokers       候选Invoker 列表
     * @param selected       已经选择的Invoker列表 【这些Invoker可能不稳定或不可用】
     * @param availablecheck 非可用是否检查
     * @return
     * @throws RpcException
     */
    private Invoker<T> reselect(LoadBalance loadbalance,
                                Invocation invocation,
                                List<Invoker<T>> invokers,
                                List<Invoker<T>> selected,
                                boolean availablecheck)
            throws RpcException {


        // 预先分配一个列表【注意：这个列表大小比候选的Invoker列表大小小1，因为候选Invoker列表中的Invoker可能在selected中或者不可用，从上一步结果可知】
        List<Invoker<T>> reselectInvokers = new ArrayList<Invoker<T>>(invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());


        //----------------------------- 先从非选择过的集合中使用负载均衡器选择一个Invoker ----------------------------/

        //  1 检查开启，选择可用的Invoker列表
        if (availablecheck) {
            // 遍历候选Invoker 集合
            for (Invoker<T> invoker : invokers) {
                // 判断是否可用
                if (invoker.isAvailable()) {
                    // 不在已经选择的Invoker列表中，则添加
                    if (selected == null || !selected.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }

            // 使用 负载均衡器 选择一个 Invoker 对象。
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }


            // 不需要检查Invoker 是否可用，选择时候，不考虑Invoker是否可用
        } else {
            // 遍历候选Invoker 集合
            for (Invoker<T> invoker : invokers) {
                // 不在 已经选择的Invoker列表中，则添加
                if (selected == null || !selected.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }

            // 使用 负载均衡器 选择一个 Invoker 对象。
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }


        //-------------------------  从已经选择过的Invoker列表中选择可用的Invoker列表，然后通过负载均衡器选择一个目标的Invoker ---------------------------/
        {
            if (selected != null) {
                for (Invoker<T> invoker : selected) {
                    // available first
                    if ((invoker.isAvailable()) && !reselectInvokers.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }

            // 使用 Loadbalance ，选择一个 Invoker 对象。
            if (!reselectInvokers.isEmpty()) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }

        return null;
    }

    /**
     * ClusterInvoker 将多个Invoker 封装成一个集群版的Invoker。
     * 说明：
     * 1 在消费者初始化期间，集群Cluster实现类为服务消费者创建 Cluster Invoker 实例，该实例将在进行远程调用时执行 #invoke 方法
     * 2 在服务消费者进行远程调用时，此时 AbstractClusterInvoker 的 invoke 方法会被调用
     * 流程：
     * 1 从服务目录拉取Invoker列表
     * 2 获取负载均衡器
     *
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        // 校验Invoker是否销毁
        checkWhetherDestroyed();

        LoadBalance loadbalance = null;

        // 设置隐式传参到Invocation中
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }

        // 获取 Invokers 【会经过Router进行过滤流程】
        List<Invoker<T>> invokers = list(invocation);

        if (invokers != null && !invokers.isEmpty()) {
            // 获取负载均衡器 ，默认是random
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl().getMethodParameter(RpcUtils.getMethodName(invocation), Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        }

        // 如果是异步操作，则添加Id 调用编号 到 invocation的attachment 中
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);

        // 执行调用，具体的由子Cluster的Invoker 实现类执行
        return doInvoke(invocation, invokers, loadbalance);
    }

    /**
     * 校验是否销毁
     */
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

    /**
     * 校验Invoker 列表是否为空
     *
     * @param invokers
     * @param invocation
     */
    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (invokers == null || invokers.isEmpty()) {
            throw new RpcException("Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    /**
     * 通过 RegistryDirectory 获取路由过滤的服务提供者Invokers集合
     *
     * @param invocation 调用信息
     * @return
     * @throws RpcException
     */
    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        List<Invoker<T>> invokers = directory.list(invocation);
        return invokers;
    }


    /**
     * 具体执行逻辑，由子类实现
     *
     * @param invocation
     * @param invokers
     * @param loadbalance
     * @return
     * @throws RpcException
     */
    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException;
}
