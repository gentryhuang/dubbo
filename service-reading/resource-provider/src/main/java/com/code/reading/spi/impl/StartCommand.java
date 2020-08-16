package com.code.reading.spi.impl;

import com.code.reading.spi.Command;

/**
 * StartCommand
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/10
 * <p>
 * desc：
 */

public class StartCommand implements Command {
    @Override
    public void execute() {
        System.out.println("start command");
    }
}
