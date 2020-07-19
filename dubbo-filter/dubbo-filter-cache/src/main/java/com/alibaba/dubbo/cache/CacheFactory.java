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
package com.alibaba.dubbo.cache;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.rpc.Invocation;

/**
 * CacheFactory，Cache 工程接口，Dubbo SPI 扩展点，默认为 "lru"
 */
@SPI("lru")
public interface CacheFactory {

    /**
     * 获得缓存对象
     *
     * @param url URL
     * @param invocation 调用信息
     * @return
     * @Adaptive("cache") 注解，基于 Dubbo SPI Adaptive 机制，加载对应的 Cache 实现，使用 URL.cache 属性。
     */
    @Adaptive("cache")
    Cache getCache(URL url, Invocation invocation);

}
