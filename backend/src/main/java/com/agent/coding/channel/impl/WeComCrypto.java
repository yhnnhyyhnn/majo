package com.agent.coding.channel.impl;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 企业微信回调消息的签名校验与 AES-CBC 解密（官方算法）。
 * 与 QwenPaw 的 wecom 渠道回调逻辑一致，纯 JDK 实现。
 */
public final class WeComCrypto {

    private WeComCrypto() {}

    /** 校验 msg_signature = sha1(字典序拼接 token/timestamp/nonce/encrypt)。 */
    public static boolean verifySignature(String token, String timestamp,
                                          String nonce, String encrypt, String signature) {
        String[] parts = {token, timestamp, nonce, encrypt};
        Arrays.sort(parts);
        String joined = String.join("", parts);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equalsIgnoreCase(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** 解密 encrypt 字段，返回明文 XML 消息体。 */
    public static String decrypt(String encodingAesKey, String encrypt) {
        try {
            byte[] keyBytes = java.util.Base64.getDecoder().decode(encodingAesKey + "=");
            byte[] ivBytes = Arrays.copyOfRange(keyBytes, 0, 16);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new IvParameterSpec(ivBytes));
            byte[] plain = cipher.doFinal(java.util.Base64.getDecoder().decode(encrypt));
            // 结构: random(16) + msg_len(4, 网络字节序) + msg + corpid
            ByteBuffer buf = ByteBuffer.wrap(plain);
            byte[] random = new byte[16];
            buf.get(random);
            int msgLen = buf.getInt();
            byte[] msg = new byte[msgLen];
            buf.get(msg);
            return new String(msg, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("wecom decrypt failed: " + e.getMessage(), e);
        }
    }
}
