import java.security.Provider;
import java.security.Security;
import java.util.Set;
import java.util.TreeSet;

public class check_crypto {
    public static void main(String[] args) {
        Set<String> algorithms = new TreeSet<>();
        for (Provider provider : Security.getProviders()) {
            for (Provider.Service service : provider.getServices()) {
                if (service.getType().equals("SecretKeyFactory")) {
                    algorithms.add(service.getAlgorithm());
                }
            }
        }
        for (String algo : algorithms) {
            System.out.println(algo);
        }
    }
}
