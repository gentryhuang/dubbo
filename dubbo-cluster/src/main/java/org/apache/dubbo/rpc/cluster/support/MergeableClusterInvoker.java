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

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.cluster.Merger;
import org.apache.dubbo.rpc.cluster.merger.MergerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.rpc.Constants.ASYNC_KEY;
import static org.apache.dubbo.rpc.Constants.MERGER_KEY;

/**
 * @param <T>
 */
@SuppressWarnings("unchecked")
public class MergeableClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger log = LoggerFactory.getLogger(MergeableClusterInvoker.class);

    public MergeableClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    /**
     * @param invocation  调用信息
     * @param invokers    Invokers 是当前调用方法对应的 Invoker 列表，该列表中的 Invoker 可能是被分组处理后的结果
     * @param loadbalance
     * @return
     * @throws RpcException
     */
    @Override
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        // 1 检测候选 Invoker 是否为空
        checkInvokers(invokers, invocation);

        // 2 获取 merger 配置项的值
        String merger = getUrl().getMethodParameter(invocation.getMethodName(), MERGER_KEY);

        // 3 判断调用的目标方法是否有 Merger 合并器
        // 如果没有则默认所有调用实例都是一个分组下的，无需做结果合并，直接找到第一个可用的 Invoker 进行调用并返回结果，然后结束流程。
        if (ConfigUtils.isEmpty(merger)) { // If a method doesn't have a merger, only invoke one Group
            for (final Invoker<T> invoker : invokers) {
                if (invoker.isAvailable()) {
                    try {
                        return invoker.invoke(invocation);
                    } catch (RpcException e) {
                        if (e.isNoInvokerAvailableAfterFilter()) {
                            log.debug("No available provider for service" + getUrl().getServiceKey() + " on group " + invoker.getUrl().getParameter(GROUP_KEY) + ", will continue to try another group.");
                        } else {
                            throw e;
                        }
                    }
                }
            }
            // 兜底，没有可用的，就调用第一个 Invoker
            return invokers.iterator().next().invoke(invocation);
        }

        // 4 确定调用方法的返回值类型
        Class<?> returnType;
        try {
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes()).getReturnType();
        } catch (NoSuchMethodException e) {
            returnType = null;
        }

        // 5 以异步方式调用每个 Invoker 对象，并将调用结果记录到 results 中。
        // results ：key 是 服务键，value 是 AsyncRpcResult
        Map<String, Result> results = new HashMap<>();
        for (final Invoker<T> invoker : invokers) {
            RpcInvocation subInvocation = new RpcInvocation(invocation, invoker);
            // 指定异步调用
            subInvocation.setAttachment(ASYNC_KEY, "true");
            results.put(invoker.getUrl().getServiceKey(), invoker.invoke(subInvocation));
        }

        Object result = null;
        List<Result> resultList = new ArrayList<Result>(results.size());

        // 6 等待结果返回
        for (Map.Entry<String, Result> entry : results.entrySet()) {
            Result asyncResult = entry.getValue();
            try {
                // AsyncRpcResult.get() 方法
                Result r = asyncResult.get();
                // 调用异常（包括超时）， 则打印error级别的日志,但最终的结果会部分数据缺失。
                if (r.hasException()) {
                    log.error("Invoke " + getGroupDescFromServiceKey(entry.getKey()) +
                                    " failed: " + r.getException().getMessage(),
                            r.getException());

                    // 将调用成功的结果保存起来
                } else {
                    resultList.add(r);
                }
            } catch (Exception e) {
                throw new RpcException("Failed to invoke service " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }

        // 7 对调用结果集为空或只有一个的情况处理，对应调用空结果集或方法返回值类型是 void ，则返回一个空结果，不需要合并
        if (resultList.isEmpty()) {
            return AsyncRpcResult.newDefaultAsyncResult(invocation);
        } else if (resultList.size() == 1) {
            return resultList.iterator().next();
        }
        if (returnType == void.class) {
            return AsyncRpcResult.newDefaultAsyncResult(invocation);
        }

        // 8 不同合并方式的处理
        // 8.1 基于指定的返回类型的方法合并结果（该方法的参数必须是返回结果的类型）
        // merger 以 . 开头，则直接使用 . 后的方法合并结果。如 merger=".addAll" ，则调用的是结果类型的原生方法，如服务方法返回类型是 List，则就调用 List.addAll 方法来合并结果。
        if (merger.startsWith(".")) {
            merger = merger.substring(1);
            Method method;
            try {
                // 反射获取指定方法对象，获取失败会抛出异常。也就是不能随意指定合并方法，合并方法必须合理。
                method = returnType.getMethod(merger, returnType);
            } catch (NoSuchMethodException e) {
                throw new RpcException("Can not merge result because missing method [ " + merger + " ] in class [ " +
                        returnType.getName() + " ]");
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }

            // 获取方法的返回结果
            result = resultList.remove(0).getValue();

            // 反射调用合并方法进行结果的合并
            try {
                // 如果返回类型不是 void，且合并方法返回类型和服务方法返回类型相同，则调用合并方法合并结果，并修改 result
                if (method.getReturnType() != void.class
                        && method.getReturnType().isAssignableFrom(result.getClass())) {
                    for (Result r : resultList) {
                        result = method.invoke(result, r.getValue());
                    }

                    // 合并方法返回类型和服务方法返回类型不同，则调用合并方法把结果合并进去即可
                } else {
                    for (Result r : resultList) {
                        method.invoke(result, r.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RpcException("Can not merge result: " + e.getMessage(), e);
            }

            // 8.2 基于 Merger 合并器合并结果
            // merger 不是以 . 开头，需要使用 Merger 合并器进行结果的合并
        } else {
            Merger resultMerger;
            // 8.2.1 merger 参数为 true 或者 default，表示使用内置的 Merger 扩展实现完成合并，即调用 MergerFactory
            if (ConfigUtils.isDefault(merger)) {
                resultMerger = MergerFactory.getMerger(returnType);

                // 8.2.2 merger参数指定了Merger的扩展名称，则使用SPI查找对应的Merger扩展实现对象
            } else {
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }

            // 8.2.3 使用 Merger 进行结果合并
            if (resultMerger != null) {
                // 这里将结果同一转成为 Object 类型
                List<Object> rets = new ArrayList<Object>(resultList.size());
                for (Result r : resultList) {
                    rets.add(r.getValue());
                }
                // 执行合并操作
                result = resultMerger.merge(
                        rets.toArray((Object[]) Array.newInstance(returnType, 0)));
            } else {
                throw new RpcException("There is no merger to merge result.");
            }
        }

        return AsyncRpcResult.newDefaultAsyncResult(result, invocation);
    }


    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
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
