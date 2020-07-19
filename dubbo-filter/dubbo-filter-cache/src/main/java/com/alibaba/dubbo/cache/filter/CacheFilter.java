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
package com.alibaba.dubbo.cache.filter;

import com.alibaba.dubbo.cache.Cache;
import com.alibaba.dubbo.cache.CacheFactory;
import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;

/**
 * CacheFilter，缓存结果的过滤器实现类
 * 配置：
 * <dubbo:reference cache="lru"/> 或者 <dubbo:service cache="lru"/> 或者 <dubbo:method cache="lru"/>
 */
@Activate(group = {Constants.CONSUMER, Constants.PROVIDER}, value = Constants.CACHE_KEY)
public class CacheFilter implements Filter {

    /**
     * 通过Dubbo SPI 机制，IOC注入 CacheFactory$Adaptive 对象
     */
    private CacheFactory cacheFactory;

    public void setCacheFactory(CacheFactory cacheFactory) {
        this.cacheFactory = cacheFactory;
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 判断方法是否开启 Cache 功能，一个服务中可能只有部分方法开启了Cache 功能
        if (cacheFactory != null && ConfigUtils.isNotEmpty(invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.CACHE_KEY))) {

            // 基于 URL + 服务方法名 为纬度，获得Cache 对象
            Cache cache = cacheFactory.getCache(invoker.getUrl(), invocation);

            if (cache != null) {
                // 获得 Cache Key
                String key = StringUtils.toArgumentString(invocation.getArguments());

                // 从缓存中获得结果，若存在就构建 RpcResult对象并返回
                Object value = cache.get(key);
                if (value != null) {
                    return new RpcResult(value);
                }

                // 放行
                Result result = invoker.invoke(invocation);

                // 若成功调用就缓存结果
                if (!result.hasException() && result.getValue() != null) {
                    cache.put(key, result.getValue());
                }
                return result;
            }
        }

        return invoker.invoke(invocation);
    }

}
