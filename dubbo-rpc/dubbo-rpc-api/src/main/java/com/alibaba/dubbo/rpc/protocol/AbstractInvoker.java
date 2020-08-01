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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractInvoker. 实现Invoker接口，抽象Invoker类，主要提供了Invoker的通用属性和#invoke(Invocation)方法的通用实现[具体实现交给子类]
 */
public abstract class AbstractInvoker<T> implements Invoker<T> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    /**
     * 接口类型
     */
    private final Class<T> type;
    /**
     * 服务URL
     */
    private final URL url;
    /**
     * 共用的隐式传参，在{@link #invoke(Invocation)}方法中使用
     */
    private final Map<String, String> attachment;

    /**
     * 是否可用
     */
    private volatile boolean available = true;

    /**
     * 是否销毁
     */
    private AtomicBoolean destroyed = new AtomicBoolean(false);

    public AbstractInvoker(Class<T> type, URL url) {
        this(type, url, (Map<String, String>) null);
    }

    public AbstractInvoker(Class<T> type, URL url, String[] keys) {
        this(type, url, convertAttachment(url, keys));
    }

    public AbstractInvoker(Class<T> type, URL url, Map<String, String> attachment) {
        if (type == null) {
            throw new IllegalArgumentException("service type == null");
        }
        if (url == null) {
            throw new IllegalArgumentException("service url == null");
        }
        this.type = type;
        this.url = url;
        this.attachment = attachment == null ? null : Collections.unmodifiableMap(attachment);
    }

    private static Map<String, String> convertAttachment(URL url, String[] keys) {
        if (keys == null || keys.length == 0) {
            return null;
        }
        Map<String, String> attachment = new HashMap<String, String>();
        for (String key : keys) {
            String value = url.getParameter(key);
            if (value != null && value.length() > 0) {
                attachment.put(key, value);
            }
        }
        return attachment;
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    protected void setAvailable(boolean available) {
        this.available = available;
    }

    /**
     * 标记已经销毁
     */
    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        setAvailable(false);
    }

    /**
     * 是否销毁
     *
     * @return
     */
    public boolean isDestroyed() {
        return destroyed.get();
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? "" : getUrl().toString());
    }

    /**
     * 服务调用，重新设置了 Invocation 中的invoker 属性和 attachment 属性
     *
     * @param inv
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invocation inv) throws RpcException {
        // if invoker is destroyed due to address refresh from registry, let's allow the current invoke to proceed
        if (destroyed.get()) {
            logger.warn("Invoker for service " + this + " on consumer " + NetUtils.getLocalHost() + " is destroyed, "
                    + ", dubbo version is " + Version.getVersion() + ", this invoker should not be used any longer");
        }

        // 为调用信息设置Invoker属性
        RpcInvocation invocation = (RpcInvocation) inv;
        // 注意： 执行到这里是真正的Invoker，之前的Invoker都是层层嵌套
        invocation.setInvoker(this);

        // 为调用信息添加公用的隐式传参，如： path,interface等 todo 用处在哪里？
        if (attachment != null && attachment.size() > 0) {
            invocation.addAttachmentsIfAbsent(attachment);
        }

        /**
         * 为调用信息添加自定义的隐式传参，使用RpcContext隐式传参需注意：
         * RpcContext 是一个临时状态记录器，当接收到 RPC 请求，或发起 RPC 请求时，RpcContext 的状态都会变化。
         * 如：A 调 B，B 再调 C，则 B 机器上，在 B 调 C 之前，RpcContext 记录的是 A 调 B 的信息，在 B 调 C 之后，RpcContext 记录的是 B 调 C 的信息。
         */
        Map<String, String> contextAttachments = RpcContext.getContext().getAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            /**
             * invocation.addAttachmentsIfAbsent(context){@link RpcInvocation#addAttachmentsIfAbsent(Map)}should not be used here,
             * because the {@link RpcContext#setAttachment(String, String)} is passed in the Filter when the call is triggered
             * by the built-in retry mechanism of the Dubbo. The attachment to update RpcContext will no longer work, which is
             * a mistake in most cases (for example, through Filter to RpcContext output traceId and spanId and other information).
             */
            invocation.addAttachments(contextAttachments);
        }

        // 如果方法是异步的就设置异步方法
        if (getUrl().getMethodParameter(invocation.getMethodName(), Constants.ASYNC_KEY, false)) {
            // 设置异步信息到 RpcInvocation#attachment 中
            invocation.setAttachment(Constants.ASYNC_KEY, Boolean.TRUE.toString());
        }
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);  // 如果是异步操作，则添加Id 调用编号 到 invocation的attachment 中


        try {
            // 执行调用，由不同协议对应具体的子类实现
            return doInvoke(invocation);
        } catch (InvocationTargetException e) { // biz exception
            Throwable te = e.getTargetException();
            if (te == null) {
                return new RpcResult(e);
            } else {
                if (te instanceof RpcException) {
                    ((RpcException) te).setCode(RpcException.BIZ_EXCEPTION);
                }
                return new RpcResult(te);
            }
        } catch (RpcException e) {
            if (e.isBiz()) {
                return new RpcResult(e);
            } else {
                throw e;
            }
        } catch (Throwable e) {
            return new RpcResult(e);
        }
    }

    protected abstract Result doInvoke(Invocation invocation) throws Throwable;

}
