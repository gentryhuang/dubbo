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
import com.alibaba.dubbo.config.spring.beans.factory.annotation.DubboConfigBindingBeanPostProcessor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.alibaba.spring.util.PropertySourcesUtils.getSubProperties;
import static com.alibaba.spring.util.PropertySourcesUtils.normalizePrefix;
import static org.springframework.beans.factory.support.BeanDefinitionBuilder.rootBeanDefinition;
import static org.springframework.beans.factory.support.BeanDefinitionReaderUtils.registerWithGeneratedName;

/**
 * {@link AbstractConfig Dubbo Config} binding Bean registrar
 *
 * @see EnableDubboConfigBinding
 * @see DubboConfigBindingBeanPostProcessor
 * @since 2.5.8
 * <p>
 * 处理 @EnableDubboConfigBinding 注解，注册相应的 Dubbo AbstractConfig 到Spring 容器
 */
public class DubboConfigBindingRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private final Log log = LogFactory.getLog(getClass());

    private ConfigurableEnvironment environment;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {

        // 获得 @EnableDubboConfigBinding注解
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfigBinding.class.getName()));
        // 根据 @EnableDubboConfigBinding注解信息 注册配置对应的 Bean Definition 对象
        registerBeanDefinitions(attributes, registry);

    }

    protected void registerBeanDefinitions(AnnotationAttributes attributes, BeanDefinitionRegistry registry) {
        // 获得prefix 属性（因为有可能有占位符，需要要解析）
        String prefix = environment.resolvePlaceholders(attributes.getString("prefix"));
        // 获得type属性，即AbstractConfig的实现类
        Class<? extends AbstractConfig> configClass = attributes.getClass("type");
        // 获的multiple属性，决定配置使用用于多BeanDefinition
        boolean multiple = attributes.getBoolean("multiple");

        registerDubboConfigBeans(prefix, configClass, multiple, registry);

    }

    /**
     * 组册dubbo Config Bean对象
     *
     * @param prefix
     * @param configClass
     * @param multiple
     * @param registry
     */
    private void registerDubboConfigBeans(String prefix,
                                          Class<? extends AbstractConfig> configClass,
                                          boolean multiple,
                                          BeanDefinitionRegistry registry) {
        // 获得prefix 开头的配置属性，以map形式返回 【environment.getPropertySources() 获得是系统属性、系统变量和@ResourceProperty注解导入的propertis配置文件中的属性k-v】
        Map<String, Object> properties = getSubProperties(environment.getPropertySources(), prefix);
        // 如果配置属性为空，就不创建ConfigBean
        if (CollectionUtils.isEmpty(properties)) {
            if (log.isDebugEnabled()) {
                log.debug("There is no property for binding to dubbo config class [" + configClass.getName()
                        + "] within prefix [" + prefix + "]");
            }
            return;
        }
        /**
         * 获得配置属性对应的Bean名字的集合，注意注解形式获得Bean的名字，如果没有配置id属性，那么就是使用Spring自动生成机制，生成的名字形式：  com.alibaba.dubbo.config.ApplicationConfig#0
         *
         * # application.properties (multiple=true的情况下可以配置的)
         * dubbo.applications.x.name=a
         * dubbo.applications.y.name=b
         *
         */
        Set<String> beanNames = multiple ? resolveMultipleBeanNames(properties) :
                Collections.singleton(resolveSingleBeanName(properties, configClass, registry));

        // 遍历Bean名字集合
        for (String beanName : beanNames) {
            // 注册Dubbo Config Bean 对象【没有设置属性值】
            registerDubboConfigBean(beanName, configClass, registry);
            // 注册Dubbo Config对象对应的DubboConfigBindingBeanPostProcessor对象，之后为Dubbo Config 设置属性值
            registerDubboConfigBindingBeanPostProcessor(prefix, beanName, multiple, registry);

        }

    }

    /**
     * 注册Dubbo ConfigBean对象
     * @param beanName
     * @param configClass
     * @param registry
     */
    private void registerDubboConfigBean(String beanName, Class<? extends AbstractConfig> configClass,
                                         BeanDefinitionRegistry registry) {

        BeanDefinitionBuilder builder = rootBeanDefinition(configClass);

        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();

        registry.registerBeanDefinition(beanName, beanDefinition);

        if (log.isInfoEnabled()) {
            log.info("The dubbo config bean definition [name : " + beanName + ", class : " + configClass.getName() +
                    "] has been registered.");
        }

    }

    /**
     * 创建的Dubbo Config对象的DubboConfigBindingBeanPostProcessor对象 【目的：实现对DubboConfig对象的配置属性设置】
     *
     * @param prefix
     * @param beanName
     * @param multiple
     * @param registry
     */
    private void registerDubboConfigBindingBeanPostProcessor(String prefix, String beanName, boolean multiple,
                                                             BeanDefinitionRegistry registry) {
        // 创建BeanDefinitionBuilder对象
        Class<?> processorClass = DubboConfigBindingBeanPostProcessor.class;
        BeanDefinitionBuilder builder = rootBeanDefinition(processorClass);

        // 添加构造方法的参数为 actualPrefix和beanName，即创建DubboConfigBindingBeanPostProcessor对象需要这两个参数
        String actualPrefix = multiple ? normalizePrefix(prefix) + beanName : prefix;
        builder.addConstructorArgValue(actualPrefix).addConstructorArgValue(beanName);
        // 获得创建的DubboConfigBindingBeanPostProcessor对象
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
         // 设置rol属性
        beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        // 注册到注册表
        registerWithGeneratedName(beanDefinition, registry);

        if (log.isInfoEnabled()) {
            log.info("The BeanPostProcessor bean definition [" + processorClass.getName()
                    + "] for dubbo config bean [name : " + beanName + "] has been registered.");
        }

    }

    @Override
    public void setEnvironment(Environment environment) {

        Assert.isInstanceOf(ConfigurableEnvironment.class, environment);

        this.environment = (ConfigurableEnvironment) environment;

    }

    /**
     * 获得配置属性对应的Bean 名字的集合
     *
     * @param properties
     * @return
     */
    // 例如： dubbo.application.${beanName}.name=dubbo-demo-annotation-provider
    private Set<String> resolveMultipleBeanNames(Map<String, Object> properties) {

        Set<String> beanNames = new LinkedHashSet<String>();

        for (String propertyName : properties.keySet()) {
            // 获取${beanName} 字符串
            int index = propertyName.indexOf(".");

            if (index > 0) {

                String beanName = propertyName.substring(0, index);

                beanNames.add(beanName);
            }

        }

        return beanNames;

    }

    /**
     * 例如： dubbo.application.name=dubbo-demo-annotation-provider
     *
     * @param properties
     * @param configClass
     * @param registry
     * @return
     */
    private String resolveSingleBeanName(Map<String, Object> properties, Class<? extends AbstractConfig> configClass,
                                         BeanDefinitionRegistry registry) {
        // 获得Bean的名称
        String beanName = (String) properties.get("id");
        // 没有没有定义，就基于Spring提供的机制生成对应的Bean的名字
        if (!StringUtils.hasText(beanName)) {
            BeanDefinitionBuilder builder = rootBeanDefinition(configClass);
            beanName = BeanDefinitionReaderUtils.generateBeanName(builder.getRawBeanDefinition(), registry);
        }

        return beanName;

    }

}
