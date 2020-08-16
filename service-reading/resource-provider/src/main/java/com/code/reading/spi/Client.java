package com.code.reading.spi;

import org.apache.dubbo.common.extension.ExtensionLoader;

/**
 * Client
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/10
 * <p>
 * desc：
 */
public class Client {

    public static void main(String[] args) {
        ExtensionLoader<Command> extensionLoader = ExtensionLoader.getExtensionLoader(Command.class);

        // 加载指定扩展名对应的扩展实现对象（获取的时候会进行实例化）
        Command startCommand = extensionLoader.getExtension("start");
        startCommand.execute();

        Command shutdownCommand = extensionLoader.getExtension("shutdown");
        shutdownCommand.execute();

    }
}
