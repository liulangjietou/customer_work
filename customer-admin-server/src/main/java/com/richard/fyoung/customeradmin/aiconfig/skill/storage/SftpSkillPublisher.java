package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SFTP 存储：把 SKILL.md 上传到 {remote-dir}/{skillCode}/SKILL.md，远端目录逐级创建。
 *
 * <p>JSch（mwiede 维护版）密码认证；低频操作，每次建连用完即断，不做连接池。
 * 仅 {@code admin.skill.storage.sftp.enabled=true} 时注册。</p>
 * @author owlzhangfq@gmail.com
 */
public class SftpSkillPublisher implements SkillContentPublisher {

    private static final Logger log = LoggerFactory.getLogger(SftpSkillPublisher.class);

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String PATH_SEPARATOR = "/";

    private final SkillStorageProperties.Sftp config;

    public SftpSkillPublisher(SkillStorageProperties.Sftp config) {
        this.config = config;
    }

    @Override
    public SkillStorageTarget target() {
        return SkillStorageTarget.SFTP;
    }

    @Override
    public void publish(String skillCode, String content) {
        Session session = null;
        ChannelSftp channel = null;
        try {
            session = openSession();
            channel = openChannel(session);
            ensureRemoteDir(channel, remoteSkillDir(config.getRemoteDir(), skillCode));
            channel.put(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                remoteSkillFile(config.getRemoteDir(), skillCode));
            log.info("sftp skill published, skillCode={}, path={}",
                skillCode, remoteSkillFile(config.getRemoteDir(), skillCode));
        } catch (Exception e) {
            throw new IllegalStateException("publish to sftp failed: " + e.getMessage(), e);
        } finally {
            disconnect(session, channel);
        }
    }

    @Override
    public void remove(String skillCode) {
        Session session = null;
        ChannelSftp channel = null;
        try {
            session = openSession();
            channel = openChannel(session);
            String dir = remoteSkillDir(config.getRemoteDir(), skillCode);
            silentRm(channel, remoteSkillFile(config.getRemoteDir(), skillCode));
            silentRmDir(channel, dir);
            log.info("sftp skill removed, skillCode={}, dir={}", skillCode, dir);
        } catch (Exception e) {
            throw new IllegalStateException("remove from sftp failed: " + e.getMessage(), e);
        } finally {
            disconnect(session, channel);
        }
    }

    private Session openSession() throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
        session.setPassword(config.getPassword());
        Properties props = new Properties();
        props.put("StrictHostKeyChecking", config.isStrictHostKeyChecking() ? "yes" : "no");
        session.setConfig(props);
        session.setTimeout(config.getTimeoutMs());
        session.connect(config.getTimeoutMs());
        return session;
    }

    private ChannelSftp openChannel(Session session) throws Exception {
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(config.getTimeoutMs());
        return channel;
    }

    /** 逐级 cd，不存在则 mkdir 后再 cd（远端目录不保证已存在）。 */
    private void ensureRemoteDir(ChannelSftp channel, String dir) throws Exception {
        for (String path : cumulativePaths(dir)) {
            try {
                channel.cd(path);
            } catch (Exception notExist) {
                channel.mkdir(path);
                channel.cd(path);
            }
        }
    }

    private void silentRm(ChannelSftp channel, String remoteFile) {
        try {
            channel.rm(remoteFile);
        } catch (Exception ignore) {
            // 文件可能本就不存在，删除是尽力而为
        }
    }

    private void silentRmDir(ChannelSftp channel, String remoteDir) {
        try {
            channel.rmdir(remoteDir);
        } catch (Exception ignore) {
            // 目录可能非空或不存在，尽力而为
        }
    }

    private void disconnect(Session session, ChannelSftp channel) {
        if (channel != null) {
            channel.disconnect();
        }
        if (session != null) {
            session.disconnect();
        }
    }

    // ---- 纯路径逻辑（可单测，不依赖连接） ----

    /** {remote-dir}/{skillCode}（去掉 remote-dir 尾部多余斜杠）。 */
    static String remoteSkillDir(String remoteDir, String skillCode) {
        return trimTrailingSlash(remoteDir) + PATH_SEPARATOR + skillCode;
    }

    /** {remote-dir}/{skillCode}/SKILL.md。 */
    static String remoteSkillFile(String remoteDir, String skillCode) {
        return remoteSkillDir(remoteDir, skillCode) + PATH_SEPARATOR + SKILL_FILE_NAME;
    }

    /** 把目录路径拆成逐级累加的路径列表，用于逐级创建；保留绝对路径前导 {@code /}。 */
    static List<String> cumulativePaths(String dir) {
        boolean absolute = dir.startsWith(PATH_SEPARATOR);
        List<String> result = new ArrayList<>();
        StringBuilder acc = new StringBuilder();
        for (String segment : dir.split(PATH_SEPARATOR)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (acc.length() == 0) {
                acc.append(absolute ? PATH_SEPARATOR + segment : segment);
            } else {
                acc.append(PATH_SEPARATOR).append(segment);
            }
            result.add(acc.toString());
        }
        return result;
    }

    private static String trimTrailingSlash(String path) {
        String p = path == null ? "" : path;
        while (p.endsWith(PATH_SEPARATOR) && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
