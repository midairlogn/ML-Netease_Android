package com.midairlogn.mlnetease;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.json.JSONArray;

public class CryptoUtils {

    private static final String AES_KEY = "e82ckenh8dichen8";

    public static String eapiEncrypt(String url, String jsonPayload) {
        try {
            String urlPath = new java.net.URL(url).getPath();
            String url2 = urlPath.replace("/eapi/", "/api/");

            String digestData = "nobody" + url2 + "use" + jsonPayload + "md5forencrypt";
            String digest = md5(digestData);

            String params = url2 + "-36cd479b6b5-" + jsonPayload + "-36cd479b6b5-" + digest;

            return aesEncrypt(params, AES_KEY);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(messageDigest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String aesEncrypt(String data, String key) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(encryptedBytes);
    }

    // Changing to lowercase to match typical Python output, just in case.
    // The reference says `HexDigest(enc)`.
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String neteaseEncryptId(String idStr) {
        try {
            String magic = "3go8&$8*3*3h0k(2)2";
            char[] magicChars = magic.toCharArray();
            char[] songIdChars = idStr.toCharArray();

            for (int i = 0; i < songIdChars.length; i++) {
                songIdChars[i] = (char) (songIdChars[i] ^ magicChars[i % magicChars.length]);
            }

            String m = new String(songIdChars);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] md5Bytes = md.digest(m.getBytes(StandardCharsets.UTF_8));

            String result = android.util.Base64.encodeToString(md5Bytes, android.util.Base64.NO_WRAP);
            result = result.replace('/', '_').replace('+', '-');
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static String getPicUrl(String picId, int size) {
        String encId = neteaseEncryptId(picId);
        return "https://p3.music.126.net/" + encId + "/" + picId + ".jpg?param=" + size + "y" + size;
    }

    public static String toHeaderJsonStr(String requestId) {
        // {"os": "pc", "appver": "", "osver": "", "deviceId": "mlncm!", "requestId": "..."}
        return "{\"os\": \"pc\", \"appver\": \"\", \"osver\": \"\", \"deviceId\": \"mlncm!\", \"requestId\": \"" + requestId + "\"}";
    }

    public static String toPayloadJsonStr(String id, String level, String headerConfig) {
        try {
            JSONObject json = new JSONObject();

            // Try to treat id as number if possible
            String trimmedId = id.trim();
            if (trimmedId.matches("-?\\d+")) {
                try {
                    json.put("ids", new org.json.JSONArray().put(Long.parseLong(trimmedId)));
                } catch (NumberFormatException e) {
                     // Fallback to string if too long for Long
                    json.put("ids", new org.json.JSONArray().put(trimmedId));
                }
            } else {
                json.put("ids", new org.json.JSONArray().put(trimmedId));
            }

            json.put("level", level);
            json.put("encodeType", "flac");

            // headerConfig is already a JSON string, but the API expects it as a string value within the JSON object
            // (i.e. double-encoded JSON if using JSONObject.put directly with a string that is JSON)
            // Python: 'header': json.dumps(config) -> config is dict, json.dumps makes it string.
            // Here headerConfig is already the string representation.
            json.put("header", headerConfig);

            if ("sky".equals(level)) {
                json.put("immerseType", "c51");
            }

            return json.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
    }
}
