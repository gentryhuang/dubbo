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
     * 令牌刷新时间间隔
     */
    private long interval;

    /**
     * 令牌数
     */
    private AtomicInteger token;

    /**
     * 限制大小
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
        this.lastResetTime = System.currentTimeMillis();
        this.token = new AtomicInteger(rate);
    }

    /**
     * 限流规则判断是否限制此次调用
     *
     * @return
     */
    public boolean isAllowable() {

        /**
         * 判断上次发放令牌的时间点到现在是否超过时间间隔，如果超过了就重新发放令牌。
         */
        long now = System.currentTimeMillis();
        if (now > lastResetTime + interval) {
            token.set(rate);
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
