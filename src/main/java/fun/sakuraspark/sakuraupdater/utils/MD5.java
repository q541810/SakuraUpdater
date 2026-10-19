package fun.sakuraspark.sakuraupdater.utils;

public class MD5 {
    // MD5计算方法
    public static String calculateMD5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        }
    }

    // MD5计算方法，接受字符串输入
    public static String calculateStringMD5(String input) {
        return calculateMD5(input.getBytes());
    }

    // MD5计算方法，接受文件路径输入
    public static String calculateMD5(java.io.File file) {
        return calculateMD5(file, null);
    }

    // MD5计算方法，接受文件路径输入；每读一块就把读到的字节数交给 progress（可为 null）
    public static String calculateMD5(java.io.File file, java.util.function.LongConsumer progress) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[65536];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
                if (progress != null) {
                    progress.accept(bytesRead);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("Error calculating MD5 for file: " + file.getPath(), e);
        }
    }
}
