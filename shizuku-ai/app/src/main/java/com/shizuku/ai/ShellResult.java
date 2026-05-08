package com.shizuku.ai;

/**
 * Shell 命令执行结果
 */
public class ShellResult {
    /** 退出码：0=成功，非0=命令执行出错，-1=执行异常（如 Shizuku 未就绪、IO异常等） */
    public final int exitCode;

    /** 命令输出内容 */
    public final String output;

    public ShellResult(int exitCode, String output) {
        this.exitCode = exitCode;
        this.output = output != null ? output : "";
    }

    /** 是否执行成功（exitCode == 0） */
    public boolean isSuccess() {
        return exitCode == 0;
    }

    @Override
    public String toString() {
        return "exitCode=" + exitCode + ", output=" + output;
    }
}
