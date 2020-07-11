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
package com.alibaba.dubbo.common.serialize.kryo;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.kryo.utils.ReflectionUtils;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.serializers.JavaSerializer;

/**
 * 继承 Kryo 类，兼容无参构造方法的Kryo的子类
 */
public class CompatibleKryo extends Kryo {

    private static final Logger logger = LoggerFactory.getLogger(CompatibleKryo.class);

    @Override
    public Serializer getDefaultSerializer(Class type) {

        if (type == null) {
            throw new IllegalArgumentException("type cannot be null.");
        }

        /**
         * 空构造方法时，使用JavaSerializer，Java 原生序列化实现 【Kryo 不支持不包含无参构造方法的类的序列化】
         */
        if (!type.isArray() && !type.isEnum() && !ReflectionUtils.checkZeroArgConstructor(type)) {
            if (logger.isWarnEnabled()) {
                logger.warn(type + " has no zero-arg constructor and this will affect the serialization performance");
            }
            return new JavaSerializer();
        }
        // 使用 Kryo 默认序列化实现
        return super.getDefaultSerializer(type);
    }
}
