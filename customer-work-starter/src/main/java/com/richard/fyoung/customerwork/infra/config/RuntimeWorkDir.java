package com.richard.fyoung.customerwork.infra.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 运行时临时工作目录的统一根：{@code ${java.io.tmpdir}/customer-work/{name}}。
 *
 * <p><b>为什么还有目录</b>：业务数据一律进 MySQL / MinIO，项目内不落任何文件。但有几处是
 * <b>第三方组件的硬约束</b>，只认真实文件系统，消除不掉：</p>
 * <ul>
 *   <li>{@code FileSystemSkillRepository}：框架的技能仓库只从目录读，故 MySQL 里的技能包要先物化；</li>
 *   <li>Harness workspace / 代码执行沙箱：文件工具、shell、docker bind mount 操作的就是真实文件；</li>
 *   <li>XXL-JOB 执行器日志：调度中心 SDK 要求一个本地日志目录。</li>
 * </ul>
 *
 * <p>放系统临时目录而不是项目目录：项目目录里出现 {@code ./data/} 会让人误以为那是需要备份的数据。</p>
 *
 * <p><b>注意：这里的内容不全是可丢弃的。</b>技能物化目录（每次启动从 MySQL 全量重建）与执行器日志确实
 * 可随时重建；但 <b>VibeCoding 会话工作区里是用户生成的代码</b>，磁盘上只有一份。那部分的权威副本由
 * {@code SessionWorkspaceStorage} 存进 MinIO（整树 tar.gz，含 {@code .git}），本目录只是它的工作副本。
 * 今后往这里放新目录时，先判断内容是不是真的可重建——不可重建的必须自带权威存储。</p>
 * @author owlzhangfq@gmail.com
 */
public final class RuntimeWorkDir {

    /** 临时根目录名，避免与系统临时目录里其它程序的产物混在一起。 */
    private static final String ROOT_NAME = "customer-work";

    private RuntimeWorkDir() {
    }

    /** 解析出 {@code ${java.io.tmpdir}/customer-work/{name}} 的绝对路径字符串（不创建目录）。 */
    public static String of(String name) {
        return root().resolve(name).toString();
    }

    /** 解析出该临时根下的子路径（不创建目录）。 */
    public static Path resolve(String name) {
        return root().resolve(name);
    }

    private static Path root() {
        return Paths.get(System.getProperty("java.io.tmpdir"), ROOT_NAME).toAbsolutePath().normalize();
    }
}
