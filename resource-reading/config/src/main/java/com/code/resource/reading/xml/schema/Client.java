package com.code.resource.reading.xml.schema;

import com.code.resource.reading.xml.schema.model.Hero;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Client
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/05/05
 * <p>
 * desc：
 */
public class Client {
    public static void main(String[] args) {
        ApplicationContext applicationContext = new ClassPathXmlApplicationContext("hero.xml");
        Hero hero = (Hero) applicationContext.getBean("hero");
        System.out.println(hero);
    }
}
