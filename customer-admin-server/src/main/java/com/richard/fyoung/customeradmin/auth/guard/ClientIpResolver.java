package com.richard.fyoung.customeradmin.auth.guard;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * 解析用于匿名接口限流、验证码绑定与登录锁定的可信来源 IP。
 *
 * <p>转发头本身由客户端控制，只有直接连接方命中显式可信代理 CIDR 时才可读取。
 * X-Forwarded-For 按“客户端, 代理1, 代理2”排列，因此从右向左剥离可信代理，
 * 取第一个不可信地址；伪造在最左侧的地址不会被采信。</p>
 *
 * <p>任何格式异常、过长、跳数过多或全链均为可信代理时都回退到直接连接地址。
 * IP 与 CIDR 解析只接受 literal，不做 DNS 查询。</p>
 * @author owlzhangfq@gmail.com
 */
public final class ClientIpResolver {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN = "unknown";
    private static final int MAX_FORWARDED_HEADER_LENGTH = 2_048;
    private static final int MAX_FORWARDED_HOPS = 16;
    private static final int MAX_IP_LITERAL_LENGTH = 64;

    private final boolean trustForwardedHeader;
    private final List<IpSubnet> trustedProxySubnets;

    public ClientIpResolver(RegistrationGuardProperties properties) {
        this(Objects.requireNonNull(properties, "properties").isTrustForwardedHeader(),
            properties.getTrustedProxyCidrs());
    }

    ClientIpResolver(boolean trustForwardedHeader, List<String> trustedProxyCidrs) {
        this.trustForwardedHeader = trustForwardedHeader;
        this.trustedProxySubnets = parseSubnets(trustedProxyCidrs);
        if (trustForwardedHeader && trustedProxySubnets.isEmpty()) {
            throw new IllegalArgumentException(
                "trustedProxyCidrs must not be empty when forwarded headers are trusted");
        }
    }

    /** 拿不到合法直连地址时返回固定串，让限流退化为全体共用一个桶而不是放行。 */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        IpAddress remoteAddress = parseLiteral(request.getRemoteAddr());
        if (remoteAddress == null) {
            return UNKNOWN;
        }
        if (!trustForwardedHeader || !isTrustedProxy(remoteAddress)) {
            return remoteAddress.canonical();
        }

        Enumeration<String> forwardedFor = request.getHeaders(HEADER_FORWARDED_FOR);
        if (forwardedFor != null && forwardedFor.hasMoreElements()) {
            return resolveForwardedChain(forwardedFor, remoteAddress);
        }

        Enumeration<String> realIpValues = request.getHeaders(HEADER_REAL_IP);
        if (realIpValues == null || !realIpValues.hasMoreElements()) {
            return remoteAddress.canonical();
        }
        String realIp = realIpValues.nextElement();
        if (realIpValues.hasMoreElements() || !StringUtils.hasText(realIp)) {
            return remoteAddress.canonical();
        }
        IpAddress realAddress = parseLiteral(realIp.trim());
        if (realAddress == null || isTrustedProxy(realAddress)) {
            return remoteAddress.canonical();
        }
        return realAddress.canonical();
    }

    private String resolveForwardedChain(Enumeration<String> headerValues, IpAddress remoteAddress) {
        List<IpAddress> hops = new ArrayList<>();
        int totalLength = 0;
        while (headerValues.hasMoreElements()) {
            String headerValue = headerValues.nextElement();
            if (headerValue == null) {
                return remoteAddress.canonical();
            }
            int separatorLength = hops.isEmpty() ? 0 : 1;
            if (headerValue.length() > MAX_FORWARDED_HEADER_LENGTH - totalLength - separatorLength) {
                return remoteAddress.canonical();
            }
            totalLength += headerValue.length() + separatorLength;
            String[] rawHops = headerValue.split(",", -1);
            if (hops.size() + rawHops.length > MAX_FORWARDED_HOPS) {
                return remoteAddress.canonical();
            }
            for (String rawHop : rawHops) {
                IpAddress hop = parseLiteral(rawHop.trim());
                if (hop == null) {
                    return remoteAddress.canonical();
                }
                hops.add(hop);
            }
        }
        for (int index = hops.size() - 1; index >= 0; index--) {
            IpAddress hop = hops.get(index);
            if (!isTrustedProxy(hop)) {
                return hop.canonical();
            }
        }
        return remoteAddress.canonical();
    }

    private boolean isTrustedProxy(IpAddress address) {
        return trustedProxySubnets.stream().anyMatch(subnet -> subnet.contains(address));
    }

    private static List<IpSubnet> parseSubnets(List<String> rawCidrs) {
        if (rawCidrs == null || rawCidrs.isEmpty()) {
            return List.of();
        }
        List<IpSubnet> parsed = new ArrayList<>(rawCidrs.size());
        for (String rawCidr : rawCidrs) {
            if (!StringUtils.hasText(rawCidr)) {
                continue;
            }
            parsed.add(parseSubnet(rawCidr.trim()));
        }
        return List.copyOf(parsed);
    }

    private static IpSubnet parseSubnet(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash != cidr.lastIndexOf('/') || slash == cidr.length() - 1) {
            throw invalidCidr(cidr);
        }
        IpAddress network = parseLiteral(cidr.substring(0, slash));
        String rawPrefix = cidr.substring(slash + 1);
        if (network == null || !isAsciiDigits(rawPrefix)) {
            throw invalidCidr(cidr);
        }
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(rawPrefix);
        } catch (NumberFormatException e) {
            throw invalidCidr(cidr);
        }
        int addressBits = network.bytes().length * Byte.SIZE;
        if (prefixLength <= 0 || prefixLength > addressBits) {
            throw invalidCidr(cidr);
        }
        return new IpSubnet(network.bytes(), prefixLength);
    }

    private static IllegalArgumentException invalidCidr(String cidr) {
        return new IllegalArgumentException("invalid trusted proxy CIDR: " + cidr);
    }

    private static IpAddress parseLiteral(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String value = rawValue.trim();
        if (value.length() > MAX_IP_LITERAL_LENGTH || !value.equals(rawValue)) {
            return null;
        }
        byte[] bytes = value.indexOf(':') >= 0 ? parseIpv6(value) : parseIpv4(value);
        if (bytes == null) {
            return null;
        }
        try {
            return new IpAddress(bytes, InetAddress.getByAddress(bytes).getHostAddress());
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("validated IP bytes are invalid", impossible);
        }
    }

    private static byte[] parseIpv4(String value) {
        String[] segments = value.split("\\.", -1);
        if (segments.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || segment.length() > 3 || !isAsciiDigits(segment)
                || segment.length() > 1 && segment.charAt(0) == '0') {
                return null;
            }
            int valuePart = Integer.parseInt(segment);
            if (valuePart > 255) {
                return null;
            }
            bytes[index] = (byte) valuePart;
        }
        return bytes;
    }

    private static byte[] parseIpv6(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean hexadecimal = current >= '0' && current <= '9'
                || current >= 'a' && current <= 'f'
                || current >= 'A' && current <= 'F';
            if (!hexadecimal && current != ':' && current != '.') {
                return null;
            }
        }
        int lastColon = value.lastIndexOf(':');
        if (value.indexOf('.') >= 0
            && (lastColon < 0 || parseIpv4(value.substring(lastColon + 1)) == null)) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isAsciiDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < '0' || current > '9') {
                return false;
            }
        }
        return true;
    }

    private record IpAddress(byte[] bytes, String canonical) {
    }

    private record IpSubnet(byte[] network, int prefixLength) {

        private boolean contains(IpAddress address) {
            byte[] candidate = address.bytes();
            if (candidate.length != network.length) {
                return false;
            }
            int wholeBytes = prefixLength / Byte.SIZE;
            for (int index = 0; index < wholeBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
