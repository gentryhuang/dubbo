package com.code.reading.spi.impl;

import com.code.reading.spi.Command;

/**
 * ShutdownCommand
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/10
 * <p>
 * desc：
 */
public class ShutdownCommand implements Command {
    @Override
    public void execute() {
        System.out.println("shutdown command");
    }
}
