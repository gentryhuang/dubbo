package com.code.resource.reading.xml.schema;

import com.code.resource.reading.xml.schema.model.Hero;
import org.springframework.beans.factory.xml.NamespaceHandlerSupport;

/**
 * HeroNamespaceHandler
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/05/05
 * <p>
 * desc：
 */
public class HeroNamespaceHandler extends NamespaceHandlerSupport {


    /**
     * 定义了<xsd:element/>对应的BeanDefinitionParser
     */
    @Override
    public void init() {
        registerBeanDefinitionParser("hero",new HeroBeanDefinitionParser(Hero.class));
    }
}
