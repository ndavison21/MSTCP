package MSTCP.vegas.more;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;

public class IP_Test {

    public static void main(String[] args) throws UnknownHostException {
        System.out.println("InetAddress.getLoopbackAddress() " + InetAddress.getLoopbackAddress().getHostAddress());
        System.out.println("InetAddress.getLocalHost() " + InetAddress.getLocalHost().getHostAddress());
        
        try {
            URL req = new URL("http://checkip.amazonaws.com");
            BufferedReader in = new BufferedReader(new InputStreamReader(req.openStream()));
            System.out.println("Amazon Request " + in.readLine());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
