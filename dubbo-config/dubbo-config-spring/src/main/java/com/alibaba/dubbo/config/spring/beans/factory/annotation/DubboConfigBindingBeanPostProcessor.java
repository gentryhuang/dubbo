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
package com.alibaba.dubbo.config.spring.beans.factory.annotation;

import com.alibaba.dubbo.common.utils.Assert;
import com.alibaba.dubbo.config.AbstractConfig;
import com.alibaba.dubbo.config.spring.context.annotation.DubboConfigBindingRegistrar;
import com.alibaba.dubbo.config.spring.context.annotation.EnableDubboConfigBinding;
import com.alibaba.dubbo.config.spring.context.properties.DefaultDubboConfigBinder;
import com.alibaba.dubbo.config.spring.context.properties.DubboConfigBinder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;

/**
 * Dubbo Config Binding {@link BeanPostProcessor}
 *
 * @see EnableDubboConfigBinding
 * @see DubboConfigBindingRegistrar
 * @since 2.5.8
 * <p>
 * 处理Dubbo AbstractConfig Bean的配置属性注入
 */

public class DubboConfigBindingBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware, InitializingBean {

    private final Log log = LogFactory.getLog(getClass());

    /**
     * The prefix of Configuration Properties // 属性配置前缀
     */
    private final String prefix;

    /**
     * Binding Bean Name  // Bean的名字
     */
    private final String beanName;

    /**
     * Dubbo 配置属性绑定器 ，用来绑定配置属性到Dubbo Config中 （底层使用Spring DataBinder实现的）
     */
    private DubboConfigBinder dubboConfigBinder;
    /**
     * 应用上下文
     */
    private ApplicationContext applicationContext;
    /**
     * 是否忽略未知的属性
     */
    private boolean ignoreUnknownFields = true;
    /**
     * 是否忽略类型不对的属性
     */
    private boolean ignoreInvalidFields = true;

    /**
     * @param prefix   the prefix of Configuration Properties
     * @param beanName the binding Bean Name
     */
    public DubboConfigBindingBeanPostProcessor(String prefix, String beanName) {
        Assert.notNull(prefix, "The prefix of Configuration Properties must not be null");
        Assert.notNull(beanName, "The name of bean must not be null");
        this.prefix = prefix;
        this.beanName = beanName;
    }

    /**
     * Bean后处理器的 前置处理方法。这里配置属性到Dubbo Config中。注意： 该方法的执行说明Dubbo Config对象的创建和属性设置完毕。
     * @param bean
     * @param beanName
     * @return
     * @throws BeansException
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
         // 选择bean的名称是 this.beanName【针对注解机制创建Bean定义，其他方式创建的Bean定义不符合条件】，并且是AbstractConfig类型的 Bean定义
        if (beanName.equals(this.beanName) && bean instanceof AbstractConfig) {

            AbstractConfig dubboConfig = (AbstractConfig) bean;
            // 设置prefix开头的配置属性到 DubboConfig中
            dubboConfigBinder.bind(prefix, dubboConfig);
            if (log.isInfoEnabled()) {
                log.info("The properties of bean [name : " + beanName + "] have been binding by prefix of " +
                        "configuration properties : " + prefix);
            }
        }
        return bean;
    }

    /**
     * Bean后处理器的后置处理方法，这里直接返回Dubbo Config 对象，不做其他的处理
     * @param bean
     * @param beanName
     * @return
     * @throws BeansException
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     *
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
         // 获得DubboConfigBinder对象
        if (dubboConfigBinder == null) {
            try {
                dubboConfigBinder = applicationContext.getBean(DubboConfigBinder.class);
            } catch (BeansException ignored) {
                if (log.isDebugEnabled()) {
                    log.debug("DubboConfigBinder Bean can't be found in ApplicationContext.");
                }
                // Use Default implementation // 创建默认的配置绑定器
                dubboConfigBinder = createDubboConfigBinder(applicationContext.getEnvironment());
            }
        }
        // 设置 是否忽略未知/无效的属性
        dubboConfigBinder.setIgnoreUnknownFields(ignoreUnknownFields);
        dubboConfigBinder.setIgnoreInvalidFields(ignoreInvalidFields);

    }

    /**
     * Create {@link DubboConfigBinder} instance.
     *
     * @param environment
     * @return {@link DefaultDubboConfigBinder}
     */
    protected DubboConfigBinder createDubboConfigBinder(Environment environment) {
        // 创建DefaultDubboConfigBinder对象
        DefaultDubboConfigBinder defaultDubboConfigBinder = new DefaultDubboConfigBinder();
        // 设置environment属性
        defaultDubboConfigBinder.setEnvironment(environment);
        return defaultDubboConfigBinder;
    }

    public boolean isIgnoreUnknownFields() {
        return ignoreUnknownFields;
    }

    public void setIgnoreUnknownFields(boolean ignoreUnknownFields) {
        this.ignoreUnknownFields = ignoreUnknownFields;
    }

    public boolean isIgnoreInvalidFields() {
        return ignoreInvalidFields;
    }

    public void setIgnoreInvalidFields(boolean ignoreInvalidFields) {
        this.ignoreInvalidFields = ignoreInvalidFields;
    }

    public DubboConfigBinder getDubboConfigBinder() {
        return dubboConfigBinder;
    }

    public void setDubboConfigBinder(DubboConfigBinder dubboConfigBinder) {
        this.dubboConfigBinder = dubboConfigBinder;
    }

}
