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
package com.alibaba.dubbo.common.serialize.support;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 序列化优化类的注册表
 */
public abstract class SerializableClassRegistry {

    /**
     * 要序列化优化的类的注册表
     */
    private static final Set<Class> registrations = new LinkedHashSet<Class>();

    /**
     * only supposed to be called at startup time
     * <p>
     * 注册要 序列化优化类。在 {@link SerializationOptimizer#getSerializableClasses()} 方法获得的类集合，会注册到这里
     */
    public static void registerClass(Class clazz) {
        registrations.add(clazz);
    }

    /**
     * 获得序列化优化类。在 Kryo,FST中调用该方法会获得需要优化的类的集合
     *
     * @return
     */
    public static Set<Class> getRegisteredClasses() {
        return registrations;
    }
}
