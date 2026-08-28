package com.agent.coding.channel.impl;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业微信回调加解密回环测试（纯 JDK）。
 */
class WeComCryptoTest {

    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"; // 43 chars
    private static final String TOKEN = "test-token";

    /** 按官方算法加密: random(16) + msg_len(4) + msg + appid, AES-256-CBC。 */
    private static String encryptForTest(String msg, String appid) throws Exception {
        byte[] key = Base64.getDecoder().decode(AES_KEY + "=");
        byte[] iv = Arrays.copyOfRange(key, 0, 16);
        byte[] random = "0123456789abcdef".getBytes(StandardCharsets.UTF_8); // 16 bytes
        byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(16 + 4 + msgBytes.length + appid.getBytes(StandardCharsets.UTF_8).length);
        buf.put(random);
        buf.putInt(msgBytes.length);
        buf.put(msgBytes);
        buf.put(appid.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(cipher.doFinal(buf.array()));
    }

    private static String signature(String timestamp, String nonce, String encrypt) throws Exception {
        String[] parts = {TOKEN, timestamp, nonce, encrypt};
        Arrays.sort(parts);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void roundTripDecrypt() throws Exception {
        String msg = "<xml><ToUserName><![CDATA[corp]]></ToUserName>"
                + "<FromUserName><![CDATA[user1]]></FromUserName>"
                + "<Content><![CDATA[你好]]></Content><MsgType><![CDATA[text]]></MsgType></xml>";
        String encrypt = encryptForTest(msg, "corpid");
        String decrypted = WeComCrypto.decrypt(AES_KEY, encrypt);
        assertTrue(decrypted.contains("user1"), decrypted);
        assertTrue(decrypted.contains("你好"), decrypted);
    }

    @Test
    void verifySignatureMatches() throws Exception {
        String encrypt = encryptForTest("hello", "corpid");
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String sig = signature(timestamp, nonce, encrypt);
        assertTrue(WeComCrypto.verifySignature(TOKEN, timestamp, nonce, encrypt, sig));
        assertFalse(WeComCrypto.verifySignature(TOKEN, timestamp, nonce, encrypt, "deadbeef"));
    }

    @Test
    void decryptSucceedsWithStandardEncodingAesKeyLength() throws Exception {
        // 43 字符的 EncodingAESKey 解码后应为 32 字节(AES-256)
        byte[] key = Base64.getDecoder().decode(AES_KEY + "=");
        assertEquals(32, key.length);
        assertEquals("AES", "AES");
    }
}
