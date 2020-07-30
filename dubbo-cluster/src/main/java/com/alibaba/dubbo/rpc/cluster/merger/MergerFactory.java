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

package com.alibaba.dubbo.rpc.cluster.merger;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.rpc.cluster.Merger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Merger工厂类，获取指定类对应的Merger对象
 */
public class MergerFactory {

    /**
     * 类的Merger映射缓存
     */
    private static final ConcurrentMap<Class<?>, Merger<?>> mergerCache = new ConcurrentHashMap<Class<?>, Merger<?>>();

    /**
     * 根据类型获取具体的Merger对象
     *
     * @param returnType 类型
     * @param <T>
     * @return
     */
    public static <T> Merger<T> getMerger(Class<T> returnType) {

        Merger result;

        // 数组类型
        if (returnType.isArray()) {
            Class type = returnType.getComponentType();
            result = mergerCache.get(type);
            if (result == null) {
                loadMergers();
                result = mergerCache.get(type);
            }

            // 获取不到就使用 ArrayMerger
            if (result == null && !type.isPrimitive()) {
                result = ArrayMerger.INSTANCE;
            }

            // 普通类型
        } else {
            result = mergerCache.get(returnType);
            if (result == null) {
                loadMergers();
                result = mergerCache.get(returnType);
            }
        }
        return result;
    }

    /**
     * 静态方法，用于初始化所有的Merger拓宽对象，并放入缓存中
     */
    static void loadMergers() {
        // 获取Merger扩展点扩展实现的名称
        Set<String> names = ExtensionLoader.getExtensionLoader(Merger.class).getSupportedExtensions();
        // 遍历扩展名，根据扩展名获取Merger 扩展实现，并放入缓存
        for (String name : names) {
            Merger m = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(name);
            mergerCache.putIfAbsent(ReflectUtils.getGenericClass(m.getClass()), m);
        }
    }

}
