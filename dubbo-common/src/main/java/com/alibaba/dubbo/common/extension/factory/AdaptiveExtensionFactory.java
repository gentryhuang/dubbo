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

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory 自适应扩展工厂
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {

    /**
     * ExtensionFactory扩展实现对象集合
     */
    private final List<ExtensionFactory> factories;

    /**
     * 构造方法，使用ExtensionLoader加载ExtensionFactory拓展点的扩展实现类对象，factories为SpiExtensionFactory和SpringExtensionFactory
     * 注意：AdaptiveExtensionFactory也是ExtensionFactory的扩展实现类，只是比较特殊，是自适应扩展类，不同于普通的扩展类
     */
    public AdaptiveExtensionFactory() {

        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();

        // 使用ExtensionLoader 加载拓展点实现类，getSupportedExtensions() 返回的是ExtensionFactory扩展点实现类对应的扩展名集合
        for (String name : loader.getSupportedExtensions()) {

            // 根据扩展名获取 ExtensionFactory 的扩展实现对象 并加入缓存中
            list.add(loader.getExtension(name));
        }
        factories = Collections.unmodifiableList(list);
    }

    /**
     * 获取目标对象，主要用于 {@link ExtensionLoader#injectExtension(java.lang.Object)} 方法中，用于获取扩展实现对象所需要的依赖属性值
     *
     * @param type object type. 扩展接口
     * @param name object name. 扩展名
     * @param <T>
     * @return
     */
    @Override
    public <T> T getExtension(Class<T> type, String name) {

        // 遍历扩展工厂对象，获取指定的扩展对象或Spring中的Bean对象
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
