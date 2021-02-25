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
package com.alibaba.dubbo.rpc.filter.tps;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认TPS限制器实现类，以服务为纬度
 */
public class DefaultTPSLimiter implements TPSLimiter {
    /**
     * StatItem 集合，即缓存每个接口的令牌数
     * key: 服务键
     * value: StatItem
     */
    private final ConcurrentMap<String, StatItem> stats = new ConcurrentHashMap<String, StatItem>();

    /**
     * 是否触发限流
     *
     * @param url        url
     * @param invocation invocation
     * @return
     */
    @Override
    public boolean isAllowable(URL url, Invocation invocation) {
        // 获得 tps 配置项，即令牌数
        int rate = url.getParameter(Constants.TPS_LIMIT_RATE_KEY, -1);
        // 获得 tps.interval 周期配置项，默认60 秒，即令牌刷新时间间隔
        long interval = url.getParameter(Constants.TPS_LIMIT_INTERVAL_KEY, Constants.DEFAULT_TPS_LIMIT_INTERVAL);
        // 获得服务键
        String serviceKey = url.getServiceKey();

        // 如果设置了令牌数，则开始限流处理
        if (rate > 0) {
            // 获取服务键对应的 StatItem 对象
            StatItem statItem = stats.get(serviceKey);
            // 不存在，则进行创建
            if (statItem == null) {
                stats.putIfAbsent(serviceKey, new StatItem(serviceKey, rate, interval));
                statItem = stats.get(serviceKey);
            }
            // 根据 tps 限流规则判断是否限制此次调用
            return statItem.isAllowable();

            // 不进行限流
        } else {
            // 移除当前服务键关联的 StatItem
            StatItem statItem = stats.get(serviceKey);
            if (statItem != null) {
                stats.remove(serviceKey);
            }
        }
        return true;
    }

}
