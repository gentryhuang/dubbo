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

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Router;

import java.util.List;

/**
 * StaticDirectory，实现AbstractDirectory抽象类。主要是将传入的 Invoker集合封装成静态的Directory对象，即它内部存放的 Invoker 是不会变动的
 */
public class StaticDirectory<T> extends AbstractDirectory<T> {

    /**
     * Invoker 集合，这个集合中的元素是不变的
     */
    private final List<Invoker<T>> invokers;

    public StaticDirectory(List<Invoker<T>> invokers) {
        this(null, invokers, null);
    }

    public StaticDirectory(List<Invoker<T>> invokers, List<Router> routers) {
        this(null, invokers, routers);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers) {
        this(url, invokers, null);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers, List<Router> routers) {
        // 确定url有效性
        super(url == null && invokers != null && !invokers.isEmpty() ? invokers.get(0).getUrl() : url, routers);
        if (invokers == null || invokers.isEmpty()) {
            throw new IllegalArgumentException("invokers == null");
        }
        this.invokers = invokers;
    }

    /**
     * 获取接口
     *
     * @return Invoker对应的接口
     */
    @Override
    public Class<T> getInterface() {
        return invokers.get(0).getInterface();
    }

    /**
     * 检测服务目录是否可用
     *
     * @return
     */
    @Override
    public boolean isAvailable() {
        // 若已经销毁，则不可用
        if (isDestroyed()) {
            return false;
        }

        // 任意一个Invoker 可用，当前服务目录就可用
        for (Invoker<T> invoker : invokers) {
            if (invoker.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // 已销毁，则跳过
        if (isDestroyed()) {
            return;
        }
        // 销毁
        super.destroy();
        // 销毁每个 Invoker
        for (Invoker<T> invoker : invokers) {
            invoker.destroy();
        }

        // 清空Invoker 集合
        invokers.clear();
    }

    /**
     * 直接返回由构造方法传入进来的Invoker 集合
     *
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    protected List<Invoker<T>> doList(Invocation invocation) throws RpcException {
        return invokers;
    }

}
