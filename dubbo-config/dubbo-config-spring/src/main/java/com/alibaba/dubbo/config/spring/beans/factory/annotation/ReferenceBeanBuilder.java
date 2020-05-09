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

import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.spring.ReferenceBean;

import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.context.ApplicationContext;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.DataBinder;

import java.beans.PropertyEditorSupport;
import java.util.Map;

import static com.alibaba.spring.util.BeanFactoryUtils.getOptionalBean;
import static com.alibaba.spring.util.ObjectUtils.of;
import static org.springframework.util.StringUtils.commaDelimitedListToStringArray;

/**
 * {@link ReferenceBean} Builder
 *
 * @since 2.5.7
 * <p>
 * 对AbstractAnnotationConfigBeanBuilder中抽象方法进行具体实现
 */
class ReferenceBeanBuilder extends AbstractAnnotationConfigBeanBuilder<Reference, ReferenceBean> {

    /**
     * 将注解的属性设置到ReferenceBean，忽略一下属性，这些属性会单独处理
     */
    static final String[] IGNORE_FIELD_NAMES = of("application", "module", "consumer", "monitor", "registry");

    private ReferenceBeanBuilder(Reference annotation, ClassLoader classLoader, ApplicationContext applicationContext) {
        super(annotation, classLoader, applicationContext);
    }

    /**
     * 配置interfaceClass
     *
     * @param reference
     * @param referenceBean
     */
    private void configureInterface(Reference reference, ReferenceBean referenceBean) {
        // 从@Reference 获得interfaceName属性，从而获得 interfaceClass 类
        Class<?> interfaceClass = reference.interfaceClass();

        if (void.class.equals(interfaceClass)) {
            interfaceClass = null;
            String interfaceClassName = reference.interfaceName();
            if (StringUtils.hasText(interfaceClassName)) {
                if (ClassUtils.isPresent(interfaceClassName, classLoader)) {
                    interfaceClass = ClassUtils.resolveClassName(interfaceClassName, classLoader);
                }
            }

        }
        // 如果获取不到，则使用interfaceClass即可【引用的服务Class类型】
        if (interfaceClass == null) {
            interfaceClass = this.interfaceClass;
        }

        Assert.isTrue(interfaceClass.isInterface(),
                "The class of field or method that was annotated @Reference is not an interface!");

        referenceBean.setInterface(interfaceClass);

    }


    /**
     * 配置ConsumerConfig
     *
     * @param reference
     * @param referenceBean
     */
    private void configureConsumerConfig(Reference reference, ReferenceBean<?> referenceBean) {
        // 获得 ConsumerConfig 对象
        String consumerBeanName = reference.consumer();
        ConsumerConfig consumerConfig = getOptionalBean(applicationContext, consumerBeanName, ConsumerConfig.class);
        // 设置到referenceBean 中
        referenceBean.setConsumer(consumerConfig);

    }

    /**
     * ReferenceBeanBuilder#build的方法调用，用来创建Reference对象。【对父类方法的重写】
     *
     * @return
     */
    @Override
    protected ReferenceBean doBuild() {
        // 创建 ReferenceBean对象
        return new ReferenceBean<Object>();
    }

    /**
     * ReferenceBean 创建后的前置配置
     * @param reference
     * @param referenceBean
     */
    @Override
    protected void preConfigureBean(Reference reference, ReferenceBean referenceBean) {
        Assert.notNull(interfaceClass, "The interface class must set first!");
        // 创建DataBinder对象,将ReferenceBean包装成DataBinder。已经进行属性绑定，绑定到的对象就是ReferenceBean
        DataBinder dataBinder = new DataBinder(referenceBean);
        // Register CustomEditors for special fields   // 注册指定属性的自定义Editor
        dataBinder.registerCustomEditor(String.class, "filter", new StringTrimmerEditor(true));
        dataBinder.registerCustomEditor(String.class, "listener", new StringTrimmerEditor(true));
        dataBinder.registerCustomEditor(Map.class, "parameters", new PropertyEditorSupport() {

            public void setAsText(String text) throws java.lang.IllegalArgumentException {
                // Trim all whitespace
                String content = StringUtils.trimAllWhitespace(text);
                if (!StringUtils.hasText(content)) { // No content , ignore directly
                    return;
                }
                // replace "=" to ","
                content = StringUtils.replace(content, "=", ",");
                // replace ":" to ","
                content = StringUtils.replace(content, ":", ",");
                // String[] to Map
                Map<String, String> parameters = CollectionUtils.toStringMap(commaDelimitedListToStringArray(content));
                setValue(parameters);
            }
        });

        // Bind annotation attributes 将注解的属性设置到ReferenceBean中，排除 {@link IGNORE_FIELD_NAMES} 属性，这些属性后续单独处理
        dataBinder.bind(new AnnotationPropertyValuesAdapter(reference, applicationContext.getEnvironment(), IGNORE_FIELD_NAMES));

    }


    @Override
    protected String resolveModuleConfigBeanName(Reference annotation) {
        return annotation.module();
    }

    @Override
    protected String resolveApplicationConfigBeanName(Reference annotation) {
        return annotation.application();
    }

    @Override
    protected String[] resolveRegistryConfigBeanNames(Reference annotation) {
        return annotation.registry();
    }

    @Override
    protected String resolveMonitorConfigBeanName(Reference annotation) {
        return annotation.monitor();
    }

    /**
     * ReferenceBean 的后置配置
     *
     * @param annotation
     * @param bean
     * @throws Exception
     */
    @Override
    protected void postConfigureBean(Reference annotation, ReferenceBean bean) throws Exception {
        // 设置 applicationContext
        bean.setApplicationContext(applicationContext);
        // 配置interface
        configureInterface(annotation, bean);
        // 配置ConsumerConfig
        configureConsumerConfig(annotation, bean);

        // 执行Bean后置属性初始化
        bean.afterPropertiesSet();

    }

    /**
     * 创建ReferenceBeanBuilder
     *
     * @param annotation
     * @param classLoader
     * @param applicationContext
     * @return
     */
    public static ReferenceBeanBuilder create(Reference annotation, ClassLoader classLoader,
                                              ApplicationContext applicationContext) {
        return new ReferenceBeanBuilder(annotation, classLoader, applicationContext);
    }

}
