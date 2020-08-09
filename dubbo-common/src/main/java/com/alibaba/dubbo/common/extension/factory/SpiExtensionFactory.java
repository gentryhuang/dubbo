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
package com.alibaba.dubbo.common.extension.factory;

import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * SpiExtensionFactory ，获取指定类型的自适应扩展对象
 */
public class SpiExtensionFactory implements ExtensionFactory {

    /**
     * 获取自适应扩展对象
     *
     * @param type object type. 扩展接口
     * @param name object name. 扩展名
     * @param <T>
     * @return
     */
    @Override
    public <T> T getExtension(Class<T> type, String name) {
        // 校验是接口类型并且有@SPI注解
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            // 加载拓展接口对应的 ExtensionLoader
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);

            // 判断当前扩展点是否有普通的扩展实现类，注意：当前扩展点存在普通的扩展实现类才会去获取对应的自适应扩展对象
            if (!loader.getSupportedExtensions().isEmpty()) {
                // 获取自适应扩展对象
                return loader.getAdaptiveExtension();
            }
        }
        return null;
    }

}
