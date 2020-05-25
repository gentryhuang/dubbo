package com.code.reading.config;

import org.apache.dubbo.config.spring.ServiceBean;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.stereotype.Component;

import java.beans.PropertyDescriptor;
import java.util.Map;

/**
 * ServiceParameterBeanPostProcessor
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @Service里面的parameters是String[]类型，但是要映射到ServiceBean的parameters属性是一个Map<String,String>类型，没法自动转换映射上去,所以报了这个错误。 那么如何解决这个问题呢？
 * <p>
 * 我们查看源码发现ServiceBean是通过: com.alibaba.dubbo.config.spring.beans.factory.annotation.ServiceAnnotationBeanPostProcessor#registerServiceBean
 * 这个BeanPostProcessor注册进来的，这里面扫描了所有打了@Service的类，把他们注册成了ServiceBean。于是我们就有了曲线救国的方法，我们在定义一个BeanPostProcessor，
 * 在ServiceAnnotationBeanPostProcessor之后执行，然后在ServiceBean真正实例化之前转换一下parameters这个参数为Map<String,String>就好了
 * @since 2020/05/25
 * <p>
 * desc： 解决@Service注解配置parameters参数时无法将String[]转化成Map<String,String>的bug
 */

public class ServiceParameterBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter implements PriorityOrdered {

    @Override
    public int getOrder() {
        return PriorityOrdered.LOWEST_PRECEDENCE;
    }

    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) throws BeansException {
        if (bean instanceof ServiceBean) {
            PropertyValue propertyValue = pvs.getPropertyValue("parameters");
            DefaultConversionService conversionService = new DefaultConversionService();


            if (propertyValue != null && propertyValue.getValue() != null && conversionService.canConvert(propertyValue.getValue().getClass(), Map.class)) {
                Map map = conversionService.convert(propertyValue.getValue(), Map.class);
                propertyValue.setConvertedValue(map);
            }
        }
        return pvs;
    }

}
