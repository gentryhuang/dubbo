package com.code.reading.spi.impl;

import com.code.reading.spi.Command;

/**
 * CommandWrapper
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/10
 * <p>
 * desc：
 */
public class CommandWrapper implements Command {

    Command command;

    /**
     * 构造方法的参数必须是扩展点类型
     *
     * @param command
     */
    public CommandWrapper(Command command) {
        this.command = command;
    }


    @Override
    public void execute() {

        System.out.println("CommandWrapper is running ...");
        // 执行扩展实现对象，注意，如果不显示调用扩展实现，那么就达不到目标结果，只会执行这个并没有真正实现的Wrapper
        command.execute();
    }
}
