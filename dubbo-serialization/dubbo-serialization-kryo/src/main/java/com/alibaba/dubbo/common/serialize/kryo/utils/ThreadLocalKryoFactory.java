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
package com.alibaba.dubbo.common.serialize.kryo.utils;

import com.esotericsoftware.kryo.Kryo;

/**
 * 继承 AbstractKryoFactory 抽象类，基于ThreadLocal 的 Kryo 工厂实现类
 */
public class ThreadLocalKryoFactory extends AbstractKryoFactory {

    /**
     * Kryo的序列化和反序列化的过程是非线程安全的，所以通过 ThreadLocal保证每个线程拥有一个Kryo对象
     */
    private final ThreadLocal<Kryo> holder = new ThreadLocal<Kryo>() {

        @Override
        protected Kryo initialValue() {
            return create();
        }
    };

    @Override
    public void returnKryo(Kryo kryo) {
        // do nothing
    }

    @Override
    public Kryo getKryo() {
        return holder.get();
    }
}
