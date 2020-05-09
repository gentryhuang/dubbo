package com.code.resource.reading.xml.schema;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;

/**
 * HeroBeanDefinitionParser
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/05/05
 * <p>
 * desc：
 */
public class HeroBeanDefinitionParser implements BeanDefinitionParser {

    /**
     * 标签对应的类
     */
    private final Class<?> beanClass;

    public HeroBeanDefinitionParser(Class<?> beanClass) {
        this.beanClass = beanClass;
    }


    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        beanDefinition.setLazyInit(false);
        beanDefinition.getPropertyValues().add("name",element.getAttribute("name"));
        beanDefinition.getPropertyValues().add("age",element.getAttribute("age"));
        // 获取Bean定义注册表
        BeanDefinitionRegistry registry = parserContext.getRegistry();
        // 注册Bean
        registry.registerBeanDefinition("hero",beanDefinition);
        return beanDefinition;
    }
}
