package com.code.resource.reading.provider.xml;

import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;

/**
 * XmlProvider
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/04/12
 * <p>
 * desc：
 */
public class XmlProvider {

    // 如果使用Multicast Registry 作为注册中心，需要debug模式运行，否则应用启动不起来

    public static String[] providerPath = {"xml/provider.xml"};
    public static String[] multiProtocolPath = {"xml/multi-protocol-provider.xml"};

    public static void main(String[] args) throws IOException {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(multiProtocolPath);
        context.start();
        System.in.read();
    }

}
