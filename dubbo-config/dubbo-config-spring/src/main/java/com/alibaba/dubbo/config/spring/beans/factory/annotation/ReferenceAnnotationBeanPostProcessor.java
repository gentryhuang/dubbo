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

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import com.alibaba.spring.beans.factory.annotation.AnnotationInjectedBeanPostProcessor;
import com.alibaba.spring.util.AnnotationUtils;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that Consumer service {@link Reference} annotated fields
 * <p>
 * 1 扫描 @Reference 注解的类，创建对应的Spring BeanDefinition 对象，从而创建Dubbo Reference Bean对象。
 * 2 就是支持@Reference 注解的属性注入或方法注入
 * @since 2.5.7
 */
public class ReferenceAnnotationBeanPostProcessor extends AnnotationInjectedBeanPostProcessor<Reference>
        implements ApplicationContextAware, ApplicationListener {

    /**
     * The bean name of {@link ReferenceAnnotationBeanPostProcessor}
     */
    public static final String BEAN_NAME = "referenceAnnotationBeanPostProcessor";

    /**
     * Cache size
     */
    private static final int CACHE_SIZE = Integer.getInteger(BEAN_NAME + ".cache.size", 32);

    /**
     * ReferenceBean 缓存 Map,key:Reference Bean 的名字
     */
    private final ConcurrentMap<String, ReferenceBean<?>> referenceBeanCache = new ConcurrentHashMap<String, ReferenceBean<?>>(CACHE_SIZE);

    /**
     * ReferenceBeanInvocationHandler 缓存 Map，key：Reference Bean的名字
     */
    private final ConcurrentHashMap<String, ReferenceBeanInvocationHandler> localReferenceBeanInvocationHandlerCache = new ConcurrentHashMap<String, ReferenceBeanInvocationHandler>(CACHE_SIZE);

    /**
     * 使用属性进行注入的 @Reference Bean 的缓存 Map。（这种方式使用的较多）
     */
    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedFieldReferenceBeanCache = new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    /**
     * 使用方法进行注入的 @Reference Bean 的缓存 Map
     */
    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedMethodReferenceBeanCache = new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    /**
     * 应用上下文
     */
    private ApplicationContext applicationContext;

    /**
     * Gets all beans of {@link ReferenceBean}
     *
     * @return non-null read-only {@link Collection}
     * @since 2.5.9
     */
    public Collection<ReferenceBean<?>> getReferenceBeans() {
        return referenceBeanCache.values();
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected field.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedFieldReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedFieldReferenceBeanCache);
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected method.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedMethodReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedMethodReferenceBeanCache);
    }

    /**
     * 获得要注入的 @Reference Bean
     *
     * @param reference
     * @param bean
     * @param beanName
     * @param injectedType
     * @param injectedElement
     * @return
     * @throws Exception
     */
    @Override
    protected Object doGetInjectedBean(Reference reference, Object bean, String beanName, Class<?> injectedType,
                                       InjectionMetadata.InjectedElement injectedElement) throws Exception {


        // 1 获得 Reference Bean 的名字
        String referencedBeanName = buildReferencedBeanName(reference, injectedType);
        // 2 创建ReferenceBean 对象 【todo 重要】
        ReferenceBean referenceBean = buildReferenceBeanIfAbsent(referencedBeanName, reference, injectedType, getClassLoader());
        // 3 缓存到 injectedFieldReferenceBeanCache 或 injectedMethodReferenceBeanCache
        cacheInjectedReferenceBean(referenceBean, injectedElement);
        // 4 创建 Proxy 代理
        Object proxy = buildProxy(referencedBeanName, referenceBean, injectedType);

        return proxy;
    }

    /**
     * 创建Proxy代理对象
     *
     * @param referencedBeanName
     * @param referenceBean
     * @param injectedType
     * @return
     */
    private Object buildProxy(String referencedBeanName, ReferenceBean referenceBean, Class<?> injectedType) {
        InvocationHandler handler = buildInvocationHandler(referencedBeanName, referenceBean);
        Object proxy = Proxy.newProxyInstance(getClassLoader(), new Class[]{injectedType}, handler);
        return proxy;
    }

    /**
     *
     * @param referencedBeanName
     * @param referenceBean
     * @return
     */
    private InvocationHandler buildInvocationHandler(String referencedBeanName, ReferenceBean referenceBean) {

        // 从 ReferenceBean的InvocationHandler缓存中获取对应的 handler对象
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.get(referencedBeanName);

        // 不存在则创建ReferenceBean的 InvocationHandler 对象
        if (handler == null) {
            handler = new ReferenceBeanInvocationHandler(referenceBean);
        }

        /** ---- 根据引用的Dubbo 服务是远程的还是本地的，做不同的处理
         * 1 远程的dubbo 服务，理论来说（不考虑对方挂掉的情况）是已经存在的，此时可以进行加载引用
         * 2 本地dubbo 服务，此时并未暴露，则先添加对应的InvocationHandler到缓存中，等后续可以通过Spring事件监听回掉机制，监听服务暴露事件，然后判断当前暴露的服务是不是
         *   在invocationHandler缓存中的服务，如果是就进行移除并初始化该服务，然后拿到该服务
         * 3 ReferenceBeanInvocationHandler是ReferenceAnnotationBeanPostProcessor的静态内部类，实现了DubboInvocationHander接口
         */
        // 如果应用上下文中已经初始化了，说明引入的服务是本地的@Service Bean ，则将引入的Dubbo服务的InvocationHandler添加到本地缓存中，不进行初始化（要想初始化，引入的服务必须是已经暴露的状态）
        if (applicationContext.containsBean(referencedBeanName)) {
            // ReferenceBeanInvocationHandler's initialization has to wait for current local @Service Bean has been exported.
            localReferenceBeanInvocationHandlerCache.put(referencedBeanName, handler);
        } else {
            // 如果应用上下文中没有，则说明是引入的是远程的服务对象，则立即初始化
            handler.init();
        }

        return handler;
    }

    /**
     * 实现了 Dubbo 的 InvocationHandler接口
     */
    private static class ReferenceBeanInvocationHandler implements InvocationHandler {

        /**
         * ReferenceBean对象
         */
        private final ReferenceBean referenceBean;
        /**
         * Bean 对象(引用的服务)
         */
        private Object bean;

        private ReferenceBeanInvocationHandler(ReferenceBean referenceBean) {
            this.referenceBean = referenceBean;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 调用 Bean 的对应的方法
            return method.invoke(bean, args);
        }

        /**
         * 1 通过初始化方法，获得 ReferenceBean.ref (引用的服务)
         * 2 调用ReferenceBean#get()方法，进行引用的Bean的初始化，最后返回引用的服务
         */
        private void init() {
            this.bean = referenceBean.get();
        }
    }

    @Override
    protected String buildInjectedObjectCacheKey(Reference reference, Object bean, String beanName,
                                                 Class<?> injectedType, InjectionMetadata.InjectedElement injectedElement) {

        String key = buildReferencedBeanName(reference, injectedType) +
                "#source=" + (injectedElement.getMember()) +
                "#attributes=" + AnnotationUtils.getAttributes(reference, getEnvironment(), true);

        return key;
    }

    /**
     * 获得 Reference Bean 的名字
     *
     * @param reference
     * @param injectedType
     * @return
     *
     * 创建 Service Bean 的名字：使用的是ServiceBeanNameBuilder 的逻辑，即和Dubbo Service Bean 的名是同一套，这个是合理的，因为引入的就是服务提供者
     */
    private String buildReferencedBeanName(Reference reference, Class<?> injectedType) {
        // 创建ServiceBeanNameBuilder
        ServiceBeanNameBuilder builder = ServiceBeanNameBuilder.create(reference, injectedType, getEnvironment());
         // 对构建的Service Bean 的名字进行解析 （todo Service Bean 那边已经解析过了，这里貌似重复解析占位符了）
        return getEnvironment().resolvePlaceholders(builder.build());
    }

    /**
     * 获得 ReferenceBean 对象
     *
     * @param referencedBeanName
     * @param reference
     * @param referencedType
     * @param classLoader
     * @return
     * @throws Exception
     */
    private ReferenceBean buildReferenceBeanIfAbsent(String referencedBeanName, Reference reference,
                                                     Class<?> referencedType, ClassLoader classLoader) throws Exception {

        // 先从ReferenceBeanCache 缓存中，获得referencedBeanName 对应的 ReferenceBean 对象
        ReferenceBean<?> referenceBean = referenceBeanCache.get(referencedBeanName);
        // 如果不存在，则进行创建，然后添加到缓存中
        if (referenceBean == null) {
            ReferenceBeanBuilder beanBuilder = ReferenceBeanBuilder
                    .create(reference, classLoader, applicationContext)
                    // 引用类型作为接口类型
                    .interfaceClass(referencedType);
            // 创建ReferenceBean【1. 创建ReferenceBean对象 2.ReferenceBean 配置】
            referenceBean = beanBuilder.build();
            referenceBeanCache.put(referencedBeanName, referenceBean);
        }

        return referenceBean;
    }

    /**
     * 缓存属性或方法方式注入的Reference
     *
     * @param referenceBean
     * @param injectedElement
     */
    private void cacheInjectedReferenceBean(ReferenceBean referenceBean,
                                            InjectionMetadata.InjectedElement injectedElement) {
        if (injectedElement.getMember() instanceof Field) {
            injectedFieldReferenceBeanCache.put(injectedElement, referenceBean);
        } else if (injectedElement.getMember() instanceof Method) {
            injectedMethodReferenceBeanCache.put(injectedElement, referenceBean);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Spring 事件监听回掉
     * @param event
     */
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        // 如果是服务暴露事件
        if (event instanceof ServiceBeanExportedEvent) {
            onServiceBeanExportEvent((ServiceBeanExportedEvent) event);
        } else if (event instanceof ContextRefreshedEvent) {
            onContextRefreshedEvent((ContextRefreshedEvent) event);
        }
    }

    /**
     * 处理服务暴露事件 【ServiceBean 暴露服务完成后，会主动触发ServiceBeanExportedEvent事件，{@link ServiceBean#onApplicationEvent(org.springframework.context.event.ContextRefreshedEvent)}】
     * @param event
     */
    private void onServiceBeanExportEvent(ServiceBeanExportedEvent event) {
        // 获得ServiceBan 对象
        ServiceBean serviceBean = event.getServiceBean();
        // 初始化对应的 ReferenceBeanInvocationHander
        initReferenceBeanInvocationHandler(serviceBean);
    }

    private void initReferenceBeanInvocationHandler(ServiceBean serviceBean) {
        // 获得引用的服务名称
        String serviceBeanName = serviceBean.getBeanName();
        // 服务已经暴露了，就移除掉
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.remove(serviceBeanName);
        // 执行处理化
        if (handler != null) {
            handler.init();
        }
    }

    private void onContextRefreshedEvent(ContextRefreshedEvent event) {

    }


    /**
     * 执行销毁逻辑
     * @throws Exception
     */
    @Override
    public void destroy() throws Exception {
        // 父类销毁
        super.destroy();
        // 清空缓存
        this.referenceBeanCache.clear();
        this.localReferenceBeanInvocationHandlerCache.clear();
        this.injectedFieldReferenceBeanCache.clear();
        this.injectedMethodReferenceBeanCache.clear();
    }
}