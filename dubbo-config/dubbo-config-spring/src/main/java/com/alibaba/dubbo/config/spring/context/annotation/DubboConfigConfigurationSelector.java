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
package com.alibaba.dubbo.config.spring.context.annotation;

import com.alibaba.dubbo.config.AbstractConfig;

import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Dubbo {@link AbstractConfig Config} Registrar
 *
 * @see EnableDubboConfig
 * @see DubboConfigConfiguration
 * @since 2.5.8
 *
 * 1 利用ImportSelector要导入哪些组件，只需要返回要导入组件的全限定类名，即 selectImports方法返回值
 * 2 如果selectImports方法返回值对应的类，它里面有使用@Bean注解的方法，那么此时给容器中导入的不只有当前返回值对应类的实例，还有该类型中
 *   加了@Bean对应的实例
 * 3 注意：给容器导入的不是 DubboConfigConfigurationSelector，因为它实现了ImportSelector接口，导入的是该类的selectImports方法中返回的值对应的类
 *
 *
 */
public class DubboConfigConfigurationSelector implements ImportSelector, Ordered {

    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {

        // 获得 @EnableDubboConfig注解的属性
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfig.class.getName()));
        //获得multiple属性
        boolean multiple = attributes.getBoolean("multiple");
        // 如果为true，则注册 DubboConfigConfiguration.Multiple Bean对象
        if (multiple) {
            return of(DubboConfigConfiguration.Multiple.class.getName());
        } else {
            // 如果为false，则注册 DubboConfigConfiguration.Single Bean对象
            return of(DubboConfigConfiguration.Single.class.getName());
        }
    }

    private static <T> T[] of(T... values) {
        return values;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }


}
