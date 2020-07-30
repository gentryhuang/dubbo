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
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.Merger;
import com.alibaba.dubbo.rpc.cluster.merger.MergerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 合并结果集Invoker，MergeableClusterInvoker串起了整个合并器逻辑。
 * 说明：
 * 1 对于Mergeable容错模式，可以在dubbo:reference标签中通过merger="true"开启，合并时可以通过group属性指定需要合并哪些分组的结果。
 * 2 默认会根据方法的返回值类型自动匹配合并起Merger，但是如果同一个类型有多个不同的合并器实现，那么就不能使用merger=true了，需要在参数中指定合并器的名字，merger="xxx"
 * 3 如果想调用 返回结果的指定方法 进行合并，如：返回结果是个Set，想调用Set#addAll方法，则可以配置 merger=".addAll" 配置来实现
 * 4 可是使用自定义方法合并结果，配置： merger="xxx"
 * 如果想
 * 调用涉及部分：
 * MergeableCuster#join -> 生成MergeableClusterInvoker 对象，处理合并逻辑 -> 使用MergerFactory工厂获取Merge接口实现 -> 完成合并逻辑
 */
@SuppressWarnings("unchecked")
public class MergeableClusterInvoker<T> implements Invoker<T> {

    private static final Logger log = LoggerFactory.getLogger(MergeableClusterInvoker.class);

    /**
     * Directory$Adaptive 对象
     */
    private final Directory<T> directory;

    /**
     * CachedThreadPool 线程池
     */
    private ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("mergeable-cluster-executor", true));


    public MergeableClusterInvoker(Directory<T> directory) {
        this.directory = directory;
    }

    /**
     * invoke调用，按组 合并返回结果
     *
     * @param invocation 调用信息
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("rawtypes")
    public Result invoke(final Invocation invocation) throws RpcException {

        // 通过服务目录Directory获得Invoker集合
        List<Invoker<T>> invokers = directory.list(invocation);

        // 获得配置的方法级别的Merger 扩展名
        String merger = getUrl().getMethodParameter(invocation.getMethodName(), Constants.MERGER_KEY);

        // 如果没有配置merger扩展，则默认所有调用实例都是一个组的，即不做结果合并。直接调用首个可用的Invoker对象。没有可用的，就调用第一个Invoker
        if (ConfigUtils.isEmpty(merger)) {
            for (final Invoker<T> invoker : invokers) {
                if (invoker.isAvailable()) {
                    return invoker.invoke(invocation);
                }
            }
            return invokers.iterator().next().invoke(invocation);
        }

        // 通过反射获得调用方法的返回类型 【后续根据这个返回类型找到对应的合并器】
        Class<?> returnType;
        try {
            returnType = getInterface().getMethod(invocation.getMethodName(), invocation.getParameterTypes()).getReturnType();
        } catch (NoSuchMethodException e) {
            returnType = null;
        }

        // 保存异步执行返回的Future，用于等待后续结果
        Map<String, Future<Result>> results = new HashMap<String, Future<Result>>();

        // 遍历Invoker列表，把RPC调用任务提交到线程池，将调用Future加入到results集合中
        for (final Invoker<T> invoker : invokers) {
            Future<Result> future = executor.submit(new Callable<Result>() {
                @Override
                public Result call() throws Exception {
                    // 执行PRC调用
                    return invoker.invoke(new RpcInvocation(invocation, invoker));
                }
            });
            results.put(invoker.getUrl().getServiceKey(), future);
        }


        Object result = null;

        // 异步执行结果集
        List<Result> resultList = new ArrayList<Result>(results.size());

        // 获得超时时间，默认1秒
        int timeout = getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);

        // 等待每个Rpc调用异步执行结果
        for (Map.Entry<String, Future<Result>> entry : results.entrySet()) {
            Future<Result> future = entry.getValue();
            try {

                // 在超时时间内阻塞等待执行结果
                Result r = future.get(timeout, TimeUnit.MILLISECONDS);

                // 执行异常，忽略
                if (r.hasException()) {
                    log.error("Invoke " + getGroupDescFromServiceKey(entry.getKey()) +
                                    " failed: " + r.getException().getMessage(),
                            r.getException());

                    // 执行成功，添加到结果集
                } else {
                    resultList.add(r);
                }
            } catch (Exception e) {
                throw new RpcException("Failed to invoke service " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }

        // 执行结果为空，就直接返回空的RpcResult
        if (resultList.isEmpty()) {
            return new RpcResult((Object) null);

            // 只有一个结果，直接返回即可，不需要合并
        } else if (resultList.size() == 1) {
            return resultList.iterator().next();
        }

        // 如果返回类型为void，直接返回空的RpcResult，不需要合并
        if (returnType == void.class) {
            return new RpcResult((Object) null);
        }


        /**
         * 一、 基于方法合并结果
         *
         * 配置方式：
         * 指定合并方法，将调用返回结果的指定方法进行合并，合并方法的参数类型必须是返回结果类型本身：
         * <dubbo:reference interface="com.xxx.MenuService" group="*">
         *     <dubbo:method name="getMenuItems" merger=".addAll" />
         * </dubbo:reference>
         */
        // 如果merger以 "." 开头，则直接通过反射调用"."后的的方法合并结果集。如：配置的是 merger=".addAll",返回类型是List，则调用List.addAll方法来合并结果集
        if (merger.startsWith(".")) {

            // 字符串截取，得到要调用的方法名
            merger = merger.substring(1);
            Method method;
            try {

                /**
                 * 通过反射获得真正的方法对象
                 * 说明：
                 *  合并方法（如例子：addAll）的参数类型必须是返回结果类型
                 */
                method = returnType.getMethod(merger, returnType);

                // 没有方法抛出异常
            } catch (NoSuchMethodException e) {
                throw new RpcException("Can not merge result because missing method [ " + merger + " ] in class [ " +
                        returnType.getClass().getName() + " ]");
            }

            // 强制放开方法访问权限
            if (!Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }

            result = resultList.remove(0).getValue();


            // 调用方法进行合并
            try {
                // 如果返回类型不为void，并且方法返回类型和结果的类型相同，则反射调用方法合并结果，并修改result
                if (method.getReturnType() != void.class && method.getReturnType().isAssignableFrom(result.getClass())) {

                    // 循环调用合并方法，进行合并
                    for (Result r : resultList) {
                        result = method.invoke(result, r.getValue());
                    }

                    // 方法返回类型不匹配，则直接把结果合并进去即可
                } else {
                    for (Result r : resultList) {
                        method.invoke(result, r.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RpcException("Can not merge result: " + e.getMessage(), e);
            }


            // 二、基于Merger合并器
        } else {
            Merger resultMerger;

            // 当 merger 为 "default" 或 "true" 时，调用 MergerFactory#getMerger(Class<T> returnType) 方法，根据返回值类型自动匹配 Merger
            if (ConfigUtils.isDefault(merger)) {

                resultMerger = MergerFactory.getMerger(returnType);

                // 获取配置的merger
            } else {
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }

            // 有merger，就进行合并
            if (resultMerger != null) {

                List<Object> rets = new ArrayList<Object>(resultList.size());
                for (Result r : resultList) {
                    rets.add(r.getValue());
                }

                // 通过Merger进行结果集的合并
                result = resultMerger.merge(rets.toArray((Object[]) Array.newInstance(returnType, 0)));

                // 没有Merger，抛出异常
            } else {
                throw new RpcException("There is no merger to merge result.");
            }
        }

        // 返回结果
        return new RpcResult(result);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        directory.destroy();
    }

    private String getGroupDescFromServiceKey(String key) {
        int index = key.indexOf("/");
        if (index > 0) {
            return "group [ " + key.substring(0, index) + " ]";
        }
        return key;
    }
}
