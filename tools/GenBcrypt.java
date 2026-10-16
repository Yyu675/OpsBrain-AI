import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GenBcrypt {
    public static void main(String[] args) {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        String hash = enc.encode("admin123");
        System.out.println(hash);
        // 自校验，避免把错误哈希写进库
        System.out.println("verify=" + enc.matches("admin123", hash));
    }
}
