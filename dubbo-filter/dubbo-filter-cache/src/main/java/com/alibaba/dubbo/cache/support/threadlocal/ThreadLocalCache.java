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
package com.alibaba.dubbo.cache.support.threadlocal;

import com.alibaba.dubbo.cache.Cache;
import com.alibaba.dubbo.common.URL;

import java.util.HashMap;
import java.util.Map;

/**
 * ThreadLocalCache，基于ThreadLocal，当前线程缓存。
 * 说明：
 * 1 相当于一个线程一个ThreadLocalCache 对象
 * 2 目前没有清理线程的机制，需要注意内存泄漏
 */
public class ThreadLocalCache implements Cache {

    /**
     * 线程变量
     */
    private final ThreadLocal<Map<Object, Object>> store;

    public ThreadLocalCache(URL url) {
        /**
         * 构造方法中初始化线程变量
         */
        this.store = new ThreadLocal<Map<Object, Object>>() {
            @Override
            protected Map<Object, Object> initialValue() {
                return new HashMap<Object, Object>();
            }
        };
    }

    @Override
    public void put(Object key, Object value) {
        store.get().put(key, value);
    }

    @Override
    public Object get(Object key) {
        return store.get().get(key);
    }

}
