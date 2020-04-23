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
package org.apache.dubbo.config.spring.context.annotation;

import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.spring.beans.factory.annotation.DubboConfigAliasPostProcessor;
import org.apache.dubbo.config.spring.context.config.NamePropertyDefaultValueDubboConfigBeanCustomizer;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

import static com.alibaba.spring.util.AnnotatedBeanDefinitionRegistryUtils.registerBeans;
import static com.alibaba.spring.util.BeanRegistrar.registerInfrastructureBean;
import static org.apache.dubbo.config.spring.context.config.NamePropertyDefaultValueDubboConfigBeanCustomizer.BEAN_NAME;

/**
 * Dubbo {@link AbstractConfig Config} {@link ImportBeanDefinitionRegistrar register}, which order can be configured
 *
 * @see EnableDubboConfig
 * @see DubboConfigConfiguration
 * @see Ordered
 * @since 2.5.8
 * <p>
 * 通过调用ImportBeanDefinitionRegistra接口中的方法，可以给容器中手动添加自定义的组件（使用BeanDefinitionRegistry中的方法）
 */
public class DubboConfigConfigurationRegistrar implements ImportBeanDefinitionRegistrar {

    /**
     * 处理@EnableDubboConfig注解，注册相应的DubboConfigConfiguration到Spring容器中
     *
     * @param importingClassMetadata 当前标注@Import注解的类的所有注解信息
     * @param registry               Bean定义的注册表，通过它可以操作容器中bean定义信息，我们可以把所有需要添加到容器中的Bean，调用它的方法手动添加到注册表中
     *                               <p>
     *                               1 可以判断容器中已经存在了哪些Bean的定义： boolean definition = registry.containsBeanDefinition("bean的名称")
     *                               2 移除某个bean定义： registr.removeBeanDefinition("bean的名称");
     *                               3 创建某个Bean定义： registr.registerBeanDefinition("baneName",BeanDefinition);
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {

        // 获得@EnableDubboConfig注解的属性
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfig.class.getName()));

        // 获得multiple属性
        boolean multiple = attributes.getBoolean("multiple");

        // 注册DubboConfigConfiguration.Single Bean对象对Spring容器
        registerBeans(registry, DubboConfigConfiguration.Single.class);

        // 注册DubboConfigConfiguration.Multiple Bean对象到Spring容器
        if (multiple) { // Since 2.6.6 https://github.com/apache/dubbo/issues/3193
            registerBeans(registry, DubboConfigConfiguration.Multiple.class);
        }

        // Register DubboConfigAliasPostProcessor
        registerDubboConfigAliasPostProcessor(registry);

        // Register NamePropertyDefaultValueDubboConfigBeanCustomizer
        registerDubboConfigBeanCustomizers(registry);

    }

    private void registerDubboConfigBeanCustomizers(BeanDefinitionRegistry registry) {
        registerInfrastructureBean(registry, BEAN_NAME, NamePropertyDefaultValueDubboConfigBeanCustomizer.class);
    }

    /**
     * Register {@link DubboConfigAliasPostProcessor}
     *
     * @param registry {@link BeanDefinitionRegistry}
     * @since 2.7.4 [Feature] https://github.com/apache/dubbo/issues/5093
     */
    private void registerDubboConfigAliasPostProcessor(BeanDefinitionRegistry registry) {
        registerInfrastructureBean(registry, DubboConfigAliasPostProcessor.BEAN_NAME, DubboConfigAliasPostProcessor.class);
    }

}
