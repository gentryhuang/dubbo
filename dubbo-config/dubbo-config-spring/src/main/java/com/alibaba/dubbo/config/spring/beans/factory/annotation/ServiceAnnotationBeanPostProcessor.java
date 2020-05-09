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

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.config.spring.context.annotation.DubboClassPathBeanDefinitionScanner;

import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.alibaba.spring.util.ObjectUtils.of;
import static org.springframework.beans.factory.support.BeanDefinitionBuilder.rootBeanDefinition;
import static org.springframework.context.annotation.AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR;
import static org.springframework.core.annotation.AnnotationUtils.findAnnotation;
import static org.springframework.util.ClassUtils.resolveClassName;

/**
 * {@link Service} Annotation
 * {@link BeanDefinitionRegistryPostProcessor Bean Definition Registry Post Processor}
 *
 * @since 2.5.8
 * <p>
 * <p>
 * 1 实现 BeanDefinitionRegistryPostProcessor、EnvironmentAware、ResourceLoaderAware、BeanClassLoaderAware 接口，扫描 @Service 注解的类，创建对应的 Spring BeanDefinition 对象，从而创建 Dubbo Service Bean 对象。
 * 2 一般来说在业务代码中，加上 @Component, @Service，@Repository, @Controller等注解就可以实现将bean注册到Spring中了，这是静态方式。
 * 我们可以利用BeanDefinitionRegistryPostProcessor，它继承BeanFactoryPostProcessor，是一种比较特殊的BeanFactoryPostProcessor，BeanDefinitionRegistry Bean定义信息的保存中心，以后BeanFactory就是按照BeanDefinitionRegistry里面保存的每一个bean定义信息创建bean实例
 */
public class ServiceAnnotationBeanPostProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware,
        ResourceLoaderAware, BeanClassLoaderAware {


    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 要扫描的包的集合
     */
    private final Set<String> packagesToScan;
    /**
     * 环境
     */
    private Environment environment;
    /**
     * 资源加载器
     */
    private ResourceLoader resourceLoader;
    /**
     * 类加载器
     */
    private ClassLoader classLoader;

    public ServiceAnnotationBeanPostProcessor(String... packagesToScan) {
        this(Arrays.asList(packagesToScan));
    }

    public ServiceAnnotationBeanPostProcessor(Collection<String> packagesToScan) {
        this(new LinkedHashSet<String>(packagesToScan));
    }

    public ServiceAnnotationBeanPostProcessor(Set<String> packagesToScan) {
        this.packagesToScan = packagesToScan;
    }

    /**
     * Bean定义注册后置处理器，可以实现自定义的注册bean定义的逻辑。【本质上，BeanDefinitionRegistry中保存的bean定义信息将来会作为BeanFactory创建实例的依据】
     *
     * @param registry
     * @throws BeansException
     */
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // 解析 packagesToScan集合，因为可能存在占位符
        Set<String> resolvedPackagesToScan = resolvePackagesToScan(packagesToScan);
        // 扫描 packagesToScan 包，创建对应的 Spring BeanDefinition 对象，从而创建 Dubbo Service Bean 对象
        if (!CollectionUtils.isEmpty(resolvedPackagesToScan)) {
            // 扫描 packagesToScan 包，创建对应的 Spring BeanDefinition 对象，从而创建 Dubbo Service Bean 对象
            registerServiceBeans(resolvedPackagesToScan, registry);
        } else {
            if (logger.isWarnEnabled()) {
                logger.warn("packagesToScan is empty , ServiceBean registry will be ignored!");
            }
        }

    }


    /**
     * Registers Beans whose classes was annotated {@link Service}
     *
     * @param packagesToScan The base packages to scan
     * @param registry       {@link BeanDefinitionRegistry}
     *                       <p>
     *                       扫描 packagesToScan 包，创建对应的 Spring BeanDefinition 对象，从而创建 Dubbo Service Bean 对象
     */
    private void registerServiceBeans(Set<String> packagesToScan, BeanDefinitionRegistry registry) {
        // 创建DubboClassPathBeanDefinitionScanner  dubbo的类路径Bean定义扫描对象
        DubboClassPathBeanDefinitionScanner scanner = new DubboClassPathBeanDefinitionScanner(registry, environment, resourceLoader);
        // 获得 BeanNameGenerator 对象，并设置 beanNameGenerator 到 scanner 中
        BeanNameGenerator beanNameGenerator = resolveBeanNameGenerator(registry);
        scanner.setBeanNameGenerator(beanNameGenerator);
        // 指定扫描器扫描带有@Service注解【Dubbo的注解】的类
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        // 遍历要扫描的包数组
        for (String packageToScan : packagesToScan) {

            // Registers @Service Bean first
            // 执行扫描
            scanner.scan(packageToScan);

            // Finds all BeanDefinitionHolders of @Service whether @ComponentScan scans or not.
            // 创建每个在packageToScan 扫描的类对应的BeanDefinitionHolder对象，返回BeanDefinitionHolder集合
            Set<BeanDefinitionHolder> beanDefinitionHolders = findServiceBeanDefinitionHolders(scanner, packageToScan, registry, beanNameGenerator);

            // 注册到registry中
            if (!CollectionUtils.isEmpty(beanDefinitionHolders)) {

                for (BeanDefinitionHolder beanDefinitionHolder : beanDefinitionHolders) {
                    // 具体注册
                    registerServiceBean(beanDefinitionHolder, registry, scanner);
                }

                if (logger.isInfoEnabled()) {
                    logger.info(beanDefinitionHolders.size() + " annotated Dubbo's @Service Components { " +
                            beanDefinitionHolders +
                            " } were scanned under package[" + packageToScan + "]");
                }

            } else {

                if (logger.isWarnEnabled()) {
                    logger.warn("No Spring Bean annotating Dubbo's @Service was found under package["
                            + packageToScan + "]");
                }

            }

        }

    }

    /**
     * It'd better to use BeanNameGenerator instance that should reference
     * {@link ConfigurationClassPostProcessor#componentScanBeanNameGenerator},
     * thus it maybe a potential problem on bean name generation.
     *
     * @param registry {@link BeanDefinitionRegistry}
     * @return {@link BeanNameGenerator} instance
     * @see SingletonBeanRegistry
     * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
     * @see ConfigurationClassPostProcessor#processConfigBeanDefinitions
     * @since 2.5.8
     */
    private BeanNameGenerator resolveBeanNameGenerator(BeanDefinitionRegistry registry) {

        BeanNameGenerator beanNameGenerator = null;

        if (registry instanceof SingletonBeanRegistry) {
            SingletonBeanRegistry singletonBeanRegistry = SingletonBeanRegistry.class.cast(registry);
            beanNameGenerator = (BeanNameGenerator) singletonBeanRegistry.getSingleton(CONFIGURATION_BEAN_NAME_GENERATOR);
        }

        if (beanNameGenerator == null) {

            if (logger.isInfoEnabled()) {

                logger.info("BeanNameGenerator bean can't be found in BeanFactory with name ["
                        + CONFIGURATION_BEAN_NAME_GENERATOR + "]");
                logger.info("BeanNameGenerator will be a instance of " +
                        AnnotationBeanNameGenerator.class.getName() +
                        " , it maybe a potential problem on bean name generation.");
            }

            beanNameGenerator = new AnnotationBeanNameGenerator();

        }

        return beanNameGenerator;

    }

    /**
     * Finds a {@link Set} of {@link BeanDefinitionHolder BeanDefinitionHolders} whose bean type annotated
     * {@link Service} Annotation.
     *
     * @param scanner       {@link ClassPathBeanDefinitionScanner}
     * @param packageToScan pachage to scan
     * @param registry      {@link BeanDefinitionRegistry}
     * @return non-null
     * @since 2.5.8
     * <p>
     * 创建每个在packageToScan扫描到的类对应的BeanDefinitionHolder对象，返回BeanDefinitionHolder集合
     */
    private Set<BeanDefinitionHolder> findServiceBeanDefinitionHolders(
            ClassPathBeanDefinitionScanner scanner,
            String packageToScan,
            BeanDefinitionRegistry registry,
            BeanNameGenerator beanNameGenerator) {
        // 获得packageToScan包下符合条件的 BeanDefinition集合
        Set<BeanDefinition> beanDefinitions = scanner.findCandidateComponents(packageToScan);

        // 创建BeanDefinitionHolder集合
        Set<BeanDefinitionHolder> beanDefinitionHolders = new LinkedHashSet<BeanDefinitionHolder>(beanDefinitions.size());
        // 遍历packageToScan包下创建的BeanDefinition集合，依次创建BeanDefinitionHolder对象
        for (BeanDefinition beanDefinition : beanDefinitions) {
            // 获得 Bean 的名字
            String beanName = beanNameGenerator.generateBeanName(beanDefinition, registry);
            // 创建BeanDefinitionHolder对象
            BeanDefinitionHolder beanDefinitionHolder = new BeanDefinitionHolder(beanDefinition, beanName);
            // 添加到Holder集合
            beanDefinitionHolders.add(beanDefinitionHolder);

        }
        return beanDefinitionHolders;

    }

    /**
     * Registers {@link ServiceBean} from new annotated {@link Service} {@link BeanDefinition}
     *
     * @param beanDefinitionHolder
     * @param registry
     * @param scanner
     * @see ServiceBean
     * @see BeanDefinition
     */
    private void registerServiceBean(BeanDefinitionHolder beanDefinitionHolder, BeanDefinitionRegistry registry,
                                     DubboClassPathBeanDefinitionScanner scanner) {
        // 从holder中取出holder，并解析Bean的类
        Class<?> beanClass = resolveClass(beanDefinitionHolder);
        // 获得@Service 注解
        Service service = findAnnotation(beanClass, Service.class);
        // 获得 注解类的接口
        Class<?> interfaceClass = resolveServiceInterfaceClass(beanClass, service);
        // 获得Bean的名字
        String annotatedServiceBeanName = beanDefinitionHolder.getBeanName();
        //  创建AbstractBeanDefinition 对象  todo 这里才真正创建ServiceBean，注意：创建的对象没有指定名称
        AbstractBeanDefinition serviceBeanDefinition = buildServiceBeanDefinition(service, interfaceClass, annotatedServiceBeanName);

        // ServiceBean Bean name
        // 重新生成Bean 的名字 【ServiceBean:${interfaceClassName}:${version}:${group}】     todo 为什么重新创建的ServiceBean名称，是要把上面的对象注册到注册表中，需要一个名称
        String beanName = generateServiceBeanName(service, interfaceClass, annotatedServiceBeanName);
        // 校验在 scanner 中是否已经存在beanName，若不存在则进行注册
        if (scanner.checkCandidate(beanName, serviceBeanDefinition)) { // check duplicated candidate bean
            registry.registerBeanDefinition(beanName, serviceBeanDefinition);

            if (logger.isInfoEnabled()) {
                logger.warn("The BeanDefinition[" + serviceBeanDefinition +
                        "] of ServiceBean has been registered with name : " + beanName);
            }

        } else {

            if (logger.isWarnEnabled()) {
                logger.warn("The Duplicated BeanDefinition[" + serviceBeanDefinition +
                        "] of ServiceBean[ bean name : " + beanName +
                        "] was be found , Did @DubboComponentScan scan to same package in many times?");
            }

        }

    }

    /**
     * Generates the bean name of {@link ServiceBean}
     *
     * @param service
     * @param interfaceClass           the class of interface annotated {@link Service}
     * @param annotatedServiceBeanName the bean name of annotated {@link Service}
     * @return ServiceBean@interfaceClassName#annotatedServiceBeanName
     * @since 2.5.9
     */
    private String generateServiceBeanName(Service service, Class<?> interfaceClass, String annotatedServiceBeanName) {
        // ServiceBean 的名称构建器
        ServiceBeanNameBuilder builder = ServiceBeanNameBuilder.create(service, interfaceClass, environment);
        return builder.build();

    }

    /**
     * 获得@Service 注解的类的接口
     *
     * @param annotatedServiceBeanClass
     * @param service
     * @return
     */
    private Class<?> resolveServiceInterfaceClass(Class<?> annotatedServiceBeanClass, Service service) {
        // 从注解本身上获得
        Class<?> interfaceClass = service.interfaceClass();
        if (void.class.equals(interfaceClass)) {
            interfaceClass = null;
            // 获得@Service 注解的interfaceName 属性
            String interfaceClassName = service.interfaceName();
            // 如果存在，获得其对应的类
            if (StringUtils.hasText(interfaceClassName)) {
                if (ClassUtils.isPresent(interfaceClassName, classLoader)) {
                    interfaceClass = resolveClassName(interfaceClassName, classLoader);
                }
            }
        }
        // 获得不到，则从被注解的类上获得其实现的首个接口【一般情况使用这个】
        if (interfaceClass == null) {
            // 获取接口列表
            Class<?>[] allInterfaces = annotatedServiceBeanClass.getInterfaces();
            // 存在的话取第一个接口
            if (allInterfaces.length > 0) {
                interfaceClass = allInterfaces[0];
            }
        }

        Assert.notNull(interfaceClass,
                "@Service interfaceClass() or interfaceName() or interface class must be present!");

        Assert.isTrue(interfaceClass.isInterface(),
                "The type that was annotated @Service is not an interface!");

        return interfaceClass;
    }

    private Class<?> resolveClass(BeanDefinitionHolder beanDefinitionHolder) {

        BeanDefinition beanDefinition = beanDefinitionHolder.getBeanDefinition();

        return resolveClass(beanDefinition);

    }

    private Class<?> resolveClass(BeanDefinition beanDefinition) {

        String beanClassName = beanDefinition.getBeanClassName();

        return resolveClassName(beanClassName, classLoader);

    }

    /**
     * 解析包路径，因为可能存在占位符
     *
     * @param packagesToScan
     * @return
     */
    private Set<String> resolvePackagesToScan(Set<String> packagesToScan) {
        Set<String> resolvedPackagesToScan = new LinkedHashSet<String>(packagesToScan.size());
        // 遍历要扫描的包路径数组
        for (String packageToScan : packagesToScan) {
            if (StringUtils.hasText(packageToScan)) {
                // 解析可能存在的占位符
                String resolvedPackageToScan = environment.resolvePlaceholders(packageToScan.trim());
                // 加入解析后路径集合中
                resolvedPackagesToScan.add(resolvedPackageToScan);
            }
        }
        return resolvedPackagesToScan;
    }

    /**
     * 创建AbstraceBeanDefinition对象
     *
     * @param service                  @Service 注解
     * @param interfaceClass
     * @param annotatedServiceBeanName 类名
     * @return
     */
    private AbstractBeanDefinition buildServiceBeanDefinition(Service service, Class<?> interfaceClass,
                                                              String annotatedServiceBeanName) {

        // ---- RuntimeBeanReference: 创建ServiceBean过程，在解析到依赖的Bean的时侯，解析器会依据依赖bean的name创建一个RuntimeBeanReference对像，将这个对像放入BeanDefinition的MutablePropertyValues中 ----/

        // 创建BeanDefinitionBuilder对象
        BeanDefinitionBuilder builder = rootBeanDefinition(ServiceBean.class);
        // 获得AbstractBeanDefinition 对象
        AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
        // 获得 MutablePropertyValues 属性。后续可以通过它添加属性，设置到BeanDefinition中，即 ServiceBean 中
        MutablePropertyValues propertyValues = beanDefinition.getPropertyValues();

        /**
         * 创建AnnotationPropertyValuesAdapter 对象，添加到propertyValues【MutablePropertyValues】中。此处，是将注解上的属性，设置到PropertyValues中。并且指定哪些属性要忽略，然后单独设置。
         * 注解中的属性能够设置到后续创建的Service Bean的对象中
         */
        String[] ignoreAttributeNames = of("provider", "monitor", "application", "module", "registry", "protocol", "interface");
        propertyValues.addPropertyValues(new AnnotationPropertyValuesAdapter(service, environment, ignoreAttributeNames));

        // References "ref" property to annotated-@Service Bean
        // 设置ServiceBean ref 属性【@Service注解标注的类的接口名对应的RuntimeBeanReference】为ServiceBean的一个属性键值对
        addPropertyReference(builder, "ref", annotatedServiceBeanName);
        // Set interface  设置ServiceBean Service 接口类全类名
        builder.addPropertyValue("interface", interfaceClass.getName());

        /**
         * Add {@link com.alibaba.dubbo.config.ProviderConfig} Bean reference
         *
         * 添加ServiceBean的 provider 属性 对应的ProviderConfig Bean 对象
         */
        String providerConfigBeanName = service.provider();
        if (StringUtils.hasText(providerConfigBeanName)) {
            addPropertyReference(builder, "provider", providerConfigBeanName);
        }

        /**
         * Add {@link com.alibaba.dubbo.config.MonitorConfig} Bean reference
         *
         * 添加ServiceBean的monitor属性对应的 MonitorConfig Bean 对象
         */
        String monitorConfigBeanName = service.monitor();
        if (StringUtils.hasText(monitorConfigBeanName)) {
            addPropertyReference(builder, "monitor", monitorConfigBeanName);
        }

        /**
         * Add {@link com.alibaba.dubbo.config.ApplicationConfig} Bean reference
         *
         * 添加ServiceBean 的 application 属性对应的 ApplicationConfig Bean 对象
         *
         */
        String applicationConfigBeanName = service.application();
        if (StringUtils.hasText(applicationConfigBeanName)) {
            addPropertyReference(builder, "application", applicationConfigBeanName);
        }

        /**
         * Add {@link com.alibaba.dubbo.config.ModuleConfig} Bean reference
         *
         * 添加ServiceBean的 module 属性对应的 ModuleConfig Bean 对象
         */
        String moduleConfigBeanName = service.module();
        if (StringUtils.hasText(moduleConfigBeanName)) {
            addPropertyReference(builder, "module", moduleConfigBeanName);
        }


        //-------------- 下面两个和上面的不一样是因为可能会有多个 ----------------------/

        /**
         * Add {@link com.alibaba.dubbo.config.RegistryConfig} Bean reference
         *
         * 添加ServiceBean的 registries 属性对应的 RegistryConfig Bean 数组（一个或多个）
         */
        String[] registryConfigBeanNames = service.registry();
        List<RuntimeBeanReference> registryRuntimeBeanReferences = toRuntimeBeanReferences(registryConfigBeanNames);
        if (!registryRuntimeBeanReferences.isEmpty()) {
            builder.addPropertyValue("registries", registryRuntimeBeanReferences);
        }

        /**
         * Add {@link com.alibaba.dubbo.config.ProtocolConfig} Bean reference
         *
         * 添加ServiceBean的 protocols 属性对应的 ProtocolConfig Bean 数组（一个或多个）
         */
        String[] protocolConfigBeanNames = service.protocol();
        List<RuntimeBeanReference> protocolRuntimeBeanReferences = toRuntimeBeanReferences(protocolConfigBeanNames);

        if (!protocolRuntimeBeanReferences.isEmpty()) {
            builder.addPropertyValue("protocols", protocolRuntimeBeanReferences);
        }

        return builder.getBeanDefinition();

    }


    /**
     * 根据名字创建RuntimeBeanReference对象
     *
     * RuntimeBeanReference ，在解析到依赖的Bean的时侯，解析器会依据依赖bean的name创建一个RuntimeBeanReference对像，将这个对像放入BeanDefinition的MutablePropertyValues中。
     *
     * @param beanNames
     * @return
     */
    private ManagedList<RuntimeBeanReference> toRuntimeBeanReferences(String... beanNames) {
        ManagedList<RuntimeBeanReference> runtimeBeanReferences = new ManagedList<RuntimeBeanReference>();
        if (!ObjectUtils.isEmpty(beanNames)) {
            for (String beanName : beanNames) {
                // 解析真正的 Bean 名字，因为可能有占位符
                String resolvedBeanName = environment.resolvePlaceholders(beanName);
                runtimeBeanReferences.add(new RuntimeBeanReference(resolvedBeanName));
            }
        }
        return runtimeBeanReferences;
    }

    /**
     * 添加属性值是引用类型
     *
     * @param builder
     * @param propertyName
     * @param beanName
     */
    private void addPropertyReference(BeanDefinitionBuilder builder, String propertyName, String beanName) {
        String resolvedBeanName = environment.resolvePlaceholders(beanName);
        builder.addPropertyReference(propertyName, resolvedBeanName);
    }


    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

}