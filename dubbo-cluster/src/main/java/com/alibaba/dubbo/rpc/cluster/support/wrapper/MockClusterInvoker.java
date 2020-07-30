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
package com.alibaba.dubbo.rpc.cluster.support.wrapper;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.support.MockInvoker;

import java.util.List;

/**
 * 由MockClusterWrapper 创建的Invoker 实现类。
 * 说明：
 * dubbo就是通过 MockClusterInvoker 来实现服务降级的
 * 使用：
 * 实际使用中会通过在dubbo-admin中设置服务降级策略，即使用配置规则进行服务治理。也可以根据需要硬编码处理。
 *
 * @param <T>
 */
public class MockClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(MockClusterInvoker.class);

    /**
     * 服务目录，这里是 RegistryDirectory
     */
    private final Directory<T> directory;
    /**
     * 容错机制 ，这里是 FailoverClusterInvoker
     */
    private final Invoker<T> invoker;

    public MockClusterInvoker(Directory<T> directory, Invoker<T> invoker) {
        this.directory = directory;
        this.invoker = invoker;
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
        this.invoker.destroy();
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    /**
     * 根据配置的 mock 参数不同，选择不同的处理方法：
     * 1 如果没有配置 mock 参数或者 mock=false，则进行远程调用
     * 2 如果配置了mock=force:return null,则直接返回null，不进行远程调用
     * 3 如果配置了mock=fail:return null，先进行远程调用，失败了再进行mock 调用
     *
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invocation invocation) throws RpcException {

        Result result = null;

        // 获取mock 配置项，有多种情况
        String value = directory.getUrl().getMethodParameter(invocation.getMethodName(), Constants.MOCK_KEY, Boolean.FALSE.toString()).trim();

        //1 没有配置 mock 参数或者 mock=false
        if (value.length() == 0 || value.equalsIgnoreCase("false")) {

            //没有mock 逻辑，直接调用原Invoker对象的 invoke 方法
            result = this.invoker.invoke(invocation);

            //2  force:xxx 直接执行 mock 逻辑，不发起远程调用，强制服务降级
        } else if (value.startsWith("force")) {
            if (logger.isWarnEnabled()) {
                logger.info("force-mock: " + invocation.getMethodName() + " force-mock enabled , url : " + directory.getUrl());
            }


            //直接调用 Mock Invoker，执行本地Mock逻辑
            result = doMockInvoke(invocation, null);

            //3  fail:xxx 表示消费方对调用服务失败后，再执行 mock 逻辑，不抛出异常，调用失败进行服务降级
        } else {

            try {
                // 调用原Invoker 对象的 invoke 方法
                result = this.invoker.invoke(invocation);
            } catch (RpcException e) {
                // 业务异常，直接抛出
                if (e.isBiz()) {
                    throw e;
                } else {
                    if (logger.isWarnEnabled()) {
                        logger.warn("fail-mock: " + invocation.getMethodName() + " fail-mock enabled , url : " + directory.getUrl(), e);
                    }

                    // 调用失败，执行 mock 逻辑，进行服务降级
                    result = doMockInvoke(invocation, e);
                }
            }
        }
        return result;
    }

    /**
     * 执行Mock 逻辑
     *
     * @param invocation
     * @param e
     * @return
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Result doMockInvoke(Invocation invocation, RpcException e) {

        Result result = null;
        Invoker<T> minvoker;

        // 选择 Mock类型的Invoker 集合
        List<Invoker<T>> mockInvokers = selectMockInvoker(invocation);

        // 如果不存在Mock Invoker集合，则创建 MockInvoker对象
        if (mockInvokers == null || mockInvokers.isEmpty()) {
            minvoker = (Invoker<T>) new MockInvoker(directory.getUrl());

            // 如果存在 Mock Invoker ，就选择第一个
        } else {
            minvoker = mockInvokers.get(0);
        }

        // 执行Mock 逻辑
        try {
            result = minvoker.invoke(invocation);
        } catch (RpcException me) {
            if (me.isBiz()) {
                result = new RpcResult(me.getCause());
            } else {
                throw new RpcException(me.getCode(), getMockExceptionMessage(e, me), me.getCause());
            }
        } catch (Throwable me) {
            throw new RpcException(getMockExceptionMessage(e, me), me.getCause());
        }
        return result;
    }

    private String getMockExceptionMessage(Throwable t, Throwable mt) {
        String msg = "mock error : " + mt.getMessage();
        if (t != null) {
            msg = msg + ", invoke error is :" + StringUtils.toString(t);
        }
        return msg;
    }

    /**
     * 从服务目录拉取Mock类型的Invoker
     * <p>
     * Return MockInvoker
     * Contract：
     * directory.list() will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
     * if directory.list() returns more than one mock invoker, only one of them will be used.
     *
     * @param invocation
     * @return
     */
    private List<Invoker<T>> selectMockInvoker(Invocation invocation) {
        List<Invoker<T>> invokers = null;
        //TODO generic invoker？
        if (invocation instanceof RpcInvocation) {

            // 设置 invocation.need.mock属性 到 attachment属性中，为了通过 directory.list方法获取 MockInvoker 集合，这个是由 MockInvokersSelector 完成。
            //Note the implicit contract (although the description is added to the interface declaration, but extensibility is a problem. The practice placed in the attachement needs to be improved)
            ((RpcInvocation) invocation).setAttachment(Constants.INVOCATION_NEED_MOCK, Boolean.TRUE.toString());

            // directory 根据 invocation 中 attachment 是否有 Constants.INVOCATION_NEED_MOCK，来判断获取的是 normal invokers or mock invokers，这个逻辑在MockInvokersSelector#route方法中
            //directory will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
            try {

                invokers = directory.list(invocation);

            } catch (RpcException e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Exception when try to invoke mock. Get mock invokers error for service:"
                            + directory.getUrl().getServiceInterface() + ", method:" + invocation.getMethodName()
                            + ", will contruct a new mock with 'new MockInvoker()'.", e);
                }
            }
        }
        return invokers;
    }

    @Override
    public String toString() {
        return "invoker :" + this.invoker + ",directory: " + this.directory;
    }
}
