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

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.spring.beans.factory.annotation.ReferenceAnnotationBeanPostProcessor;
import com.alibaba.dubbo.config.spring.beans.factory.annotation.ServiceAnnotationBeanPostProcessor;
import com.alibaba.dubbo.config.spring.util.BeanRegistrar;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.springframework.beans.factory.support.BeanDefinitionBuilder.rootBeanDefinition;

/**
 * Dubbo {@link DubboComponentScan} Bean Registrar
 *
 * @see Service
 * @see DubboComponentScan
 * @see ImportBeanDefinitionRegistrar
 * @see ServiceAnnotationBeanPostProcessor
 * @see ReferenceAnnotationBeanPostProcessor
 * @since 2.5.7
 */
public class DubboComponentScanRegistrar implements ImportBeanDefinitionRegistrar {

    /**
     * @param importingClassMetadata @DubboComponentScan 注解的信息
     * @param registry               Bean定义注册
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        /**
         * 获得要扫描的包
         */
        Set<String> packagesToScan = getPackagesToScan(importingClassMetadata);
        /**
         * 创建 ServiceAnnotationBeanPostProcessor Bean 对象，后续扫描 `@Service` 注解的类，创建对应的 Service Bean 对象
         */
        registerServiceAnnotationBeanPostProcessor(packagesToScan, registry);
        /**
         * 创建 ReferenceAnnotationBeanPostProcessor Bean 对象，后续扫描 `@Reference` 注解的类，创建对应的 Reference Bean 对象
         */
        registerReferenceAnnotationBeanPostProcessor(registry);

    }

    /**
     * Registers {@link ServiceAnnotationBeanPostProcessor}
     *
     * @param packagesToScan packages to scan without resolving placeholders
     * @param registry       {@link BeanDefinitionRegistry}
     * @since 2.5.8
     * <p>
     * 创建 ServiceAnnotationBeanPostProcessor Bean 对象，后续扫描 @Service 注解的类，创建对应的 Service Bean 对象
     */
    private void registerServiceAnnotationBeanPostProcessor(Set<String> packagesToScan, BeanDefinitionRegistry registry) {
        // 创建ServiceAnnotationBeanPostProcessor的BeanDefinitionBuilder 对象
        BeanDefinitionBuilder builder = rootBeanDefinition(ServiceAnnotationBeanPostProcessor.class);
        // 设置构造方法参数为 packagesToScan，即ServiceAnnotationBeanPostProcessor的BeanDefinitionBuilder 扫描该包
        builder.addConstructorArgValue(packagesToScan);
        // 设置 role 属性
        builder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        // 获得 AbstractBeanDefinition 对象
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        // 注册到注册表中
        BeanDefinitionReaderUtils.registerWithGeneratedName(beanDefinition, registry);

    }

    /**
     * Registers {@link ReferenceAnnotationBeanPostProcessor} into {@link BeanFactory}
     *
     * @param registry {@link BeanDefinitionRegistry}
     *                 <ul>
     *                  <li>1 创建 ReferenceAnnotationBeanPostProcessor Bean 对象，后续扫描 @Reference 注解的类，创建对应的 Reference Bean 对象。</li>
     *                  <li>  2 ReferenceAnnotationBeanPostProcessor创建不需要BeanDefinitionBuilder，是因为要扫描包的动作已经被 ServiceAnnotationBeanPostProcessor的BeanDefinitionBuilder做了，所以可以直接创建BeanDefinition并注册到注册表</li>
     *                 </ul>
     */
    private void registerReferenceAnnotationBeanPostProcessor(BeanDefinitionRegistry registry) {

        // Register @Reference Annotation Bean Processor
        BeanRegistrar.registerInfrastructureBean(registry, ReferenceAnnotationBeanPostProcessor.BEAN_NAME, ReferenceAnnotationBeanPostProcessor.class);

    }

    /**
     * 获得 DubboComponentScan注解扫描的包
     *
     * @param metadata
     * @return
     */
    private Set<String> getPackagesToScan(AnnotationMetadata metadata) {
        // 获得 @DubboComponentScan 注解
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(DubboComponentScan.class.getName()));
        // 获得basePackages 属性值
        String[] basePackages = attributes.getStringArray("basePackages");
        // 获得basePackageClasses属性值
        Class<?>[] basePackageClasses = attributes.getClassArray("basePackageClasses");
        // 获得默认属性（basePackages的默认属性）
        String[] value = attributes.getStringArray("value");
        // 将属性添加到 packagesToScan 集合中
        Set<String> packagesToScan = new LinkedHashSet<String>(Arrays.asList(value));
        packagesToScan.addAll(Arrays.asList(basePackages));
        // 处理 扫描的类的数组 ，得到每个类的包名，然后添加到 包路径数组中
        for (Class<?> basePackageClass : basePackageClasses) {
            packagesToScan.add(ClassUtils.getPackageName(basePackageClass));
        }

        // packagesToScan 为空的话，则默认使用DubboComponentScan注解类所在的包做为扫描包
        if (packagesToScan.isEmpty()) {
            return Collections.singleton(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packagesToScan;
    }

}
