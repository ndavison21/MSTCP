package MSTCP.vegas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Random;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class Utils {
    public static final boolean delayAndDrop = true;
    public static final Random rand          = new Random();
    public static final int latency          = 1500;
    public static final int latency_variance = 1000;
    public static final double p_drop        = 0.05;

    public static final int noOfSources = 1;
    public static final int pktSize     = 1000; // TCP Header: 20, blocks <= 980, so 1000 Bytes total
    public static final int headerSize  = 28;  // TCP Header (with no options): 20 bytes. 8 bytes for timestamps
    public static final int blockSize   = pktSize - headerSize;  // Send blocks of 980 bytes
    public static final int requestSize = headerSize + 4; // header + block requested
    
    public static final int synAttempts  = Integer.MAX_VALUE;
    public static final int synTimeout   = Integer.MAX_VALUE;
    public static final int dataAttempts = 3;
    public static final int dataTimeout  = Integer.MAX_VALUE;
    
    public static final int total_alpha = 10;
    
//    public static String getIPAddress(Logger logger) {
//        URL req;
//        BufferedReader in;
//        String addr = null;
//        
//        try {
//            req = new URL("http://checkip.amazonaws.com");
//            in = new BufferedReader(new InputStreamReader(req.openStream()));
//            addr = in.readLine();
//        } catch (IOException e) {
//            logger.log(Level.SEVERE, e.getMessage(), e);
//            System.exit(1);
//        }
//
//       return addr; //you get the IP as a String
//    }
    
    public static String getIPAddress(Logger logger) {
        return "127.0.0.1";
    }
    
    
    public static boolean delayAndDrop(Logger logger) {
        if (delayAndDrop) {
            try {
                Thread.sleep(latency + rand.nextInt(latency_variance) - latency_variance / 2);
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
                System.exit(1);
            }
            return (rand.nextDouble() < p_drop);
        }
        
        return false;
    }
    
    public static Logger getLogger(String filename) {
        Logger logger = Logger.getLogger(filename);
        try {
            FileHandler handler = new FileHandler("./logs/" + filename + ".log", 1048576, 1, false);
            handler.setFormatter(new SimpleFormatter());
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            logger.setLevel(Level.ALL);
        } catch (SecurityException | IOException e) {
            System.err.println("MSTCPReceiver: Unable to Connect to Logger");
            e.printStackTrace();
            return null;
        }
        return logger;
    }
}
