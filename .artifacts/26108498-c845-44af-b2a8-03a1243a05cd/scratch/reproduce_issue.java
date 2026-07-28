import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Arrays;

public class reproduce_issue {
    public static void main(String[] args) throws Exception {
        String phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        byte[] backupSalt = "artifact_backup_v1_salt".getBytes();
        byte[] exportSalt = "artifact_export_v1_salt".getBytes();

        byte[] backupKey = deriveKey(phrase, backupSalt);
        byte[] exportKey = deriveKey(phrase, exportSalt);

        System.out.println("Backup Key: " + bytesToHex(backupKey));
        System.out.println("Export Key: " + bytesToHex(exportKey));
        System.out.println("Same: " + Arrays.equals(backupKey, exportKey));
    }

    public static byte[] deriveKey(String passphrase, byte[] salt) throws Exception {
        int iterations = 600000;
        int keyLength = 256;
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, keyLength);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
