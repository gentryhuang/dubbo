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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统计项类
 */
class StatItem {

    /**
     * 统计名，目前使用服务键
     */
    private String name;

    /**
     * 最后重置时间
     */
    private long lastResetTime;
    /**
     * 令牌刷新时间间隔，即重置 token 值的时间周期，这样就实现了在 interval 时间段内能够通过 rate 个请求的效果。
     */
    private long interval;

    /**
     * 令牌数,初始值为 rate 值，每通过一个请求 token 递减一，当减为 0 时，不再通过任何请求，实现限流的作用。
     */
    private AtomicInteger token;

    /**
     * 一段时间内能通过的 TPS 上限
     */
    private int rate;

    /**
     * 构造方法
     *
     * @param name     服务键
     * @param rate     限制大小
     * @param interval 限制周期
     */
    StatItem(String name, int rate, long interval) {
        this.name = name;
        this.rate = rate;
        this.interval = interval;
        // 记录时间戳
        this.lastResetTime = System.currentTimeMillis();
        this.token = new AtomicInteger(rate);
    }

    /**
     * 限流规则判断是否限制此次调用
     *
     * @return
     */
    public boolean isAllowable() {
        // 周期性重置token
        long now = System.currentTimeMillis();
        if (now > lastResetTime + interval) {
            token.set(rate);
            // 记录最近一次重置token的时间戳
            lastResetTime = now;
        }

        // CAS，直到获得一个令牌，或者没有足够的令牌才结束
        int value = token.get();
        boolean flag = false;
        while (value > 0 && !flag) {
            flag = token.compareAndSet(value, value - 1);
            value = token.get();
        }

        // 是否允许访问 【取决是否能够拿到令牌】
        return flag;
    }

    long getLastResetTime() {
        return lastResetTime;
    }

    int getToken() {
        return token.get();
    }

    @Override
    public String toString() {
        return new StringBuilder(32).append("StatItem ")
                .append("[name=").append(name).append(", ")
                .append("rate = ").append(rate).append(", ")
                .append("interval = ").append(interval).append("]")
                .toString();
    }

}
