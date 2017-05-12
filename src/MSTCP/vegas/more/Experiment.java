package MSTCP.vegas.more;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.text.DecimalFormat;
import java.util.Vector;
import java.util.logging.Handler;
import java.util.logging.Level;

public class Experiment {
    public static void main(String[] args) throws InterruptedException, IOException {
        /** Configuring Experiment Parameters **/
        int i = 0;
        String directory = args[i++];
        Utils.noOfSources = Integer.parseInt(args[i++]);
        Utils.noOfPaths = Integer.parseInt(args[i++]);
        Utils.noOfConnections = Integer.parseInt(args[i++]);
        Utils.batchSize = Integer.parseInt(args[i++]);
        double p = Double.parseDouble(args[i++]);
        Utils.packetLimit = Integer.parseInt(args[i++]);
        int experiment = Integer.parseInt(args[i++]);
        String file = args.length > i ? args[i++] : "gb.jpg";
        
        if (Utils.packetLimit == -1)
            Utils.packetLimit = Integer.MAX_VALUE;
        
        DecimalFormat df = new DecimalFormat("0.00");
        String path = String.format("../../evaluation/data/%s/s%d_p%d_c%d_b%d_p%s/", directory, Utils.noOfSources, Utils.noOfPaths, Utils.noOfConnections, Utils.batchSize, df.format(p));
        
        if (p <= 0)
            Utils.p_drop = 0;
        else {
            Utils.p_drop = (1.0/2.0) * (2 - Math.sqrt(4 - (4 * p)));
            if (Utils.p_drop <= 0 || Utils.p_drop >= 1)
                Utils.p_drop = (1.0/2.0) * (2 + Math.sqrt(4 - (4 * p)));
        }
        
        /** Removing Previous Log Files **/
        File logs = new File("./logs");
        for(File log: logs.listFiles()) 
            if (!log.isDirectory()) 
                log.delete();
        
        File res = new File("./logs/" + path + "#" + experiment + ".log");
        if (res.exists())
            res.delete();
        
        Utils.requests_logger = Utils.getLogger("#" + experiment + "_requests", path, Level.ALL, Integer.MAX_VALUE);
        Utils.received_logger = Utils.getLogger("#" + experiment + "_received", path, Level.ALL, Integer.MAX_VALUE);
        
        /** Source Vector **/    
        int sourcePort  = 16000;
        final Vector<SourceInformation> sources  = new Vector<SourceInformation>(Utils.noOfSources);
        for (i=1; i<=Utils.noOfSources; i++) {
            sourcePort = 16000 + (i-1) * 2;
            int[] ports = new int[Utils.noOfPaths];
            for (int j=0; j<Utils.noOfPaths; j++) {
                ports[j] = sourcePort++;
            }
            sources.add(new SourceInformation("127.0.0." + i, ports));
        }
               
        /** Requester Router **/
        int routerPort = 15000;
        
        final int reqRouterPort = routerPort++;
        (new Thread() {
            public void run() {
                try {
                    new RequesterForwarder(reqRouterPort);
                } catch (SocketException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }).start();
        
        /** Middle Routers **/
        routerPort = 15001;
        for (SourceInformation source: sources) {
            for (int j=0; j<source.ports.size(); j++) {
                final int midRouterPort = routerPort + j;
                (new Thread() {
                    public void run() {
                        try {
                            new MiddleForwarder(midRouterPort, 20, Utils.p_drop);
                        } catch (SocketException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }
                }).start();
            }
            routerPort += 2;
        }
        
        /** Responder Routers and Responders **/
        routerPort = 15005;
        for (final SourceInformation source: sources) {
            /** Responder Router **/
            final int resRouterPort = routerPort++;
            (new Thread() {
                public void run() {
                    try {
                        new ResponderForwarder(resRouterPort, resRouterPort == 15005 ? Utils.packetLimit : Integer.MAX_VALUE);
                    } catch (SocketException e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }
            }).start();
            
            
            /** Responders **/
            for (final int resPort: source.ports.keySet()) {
                (new Thread() {
                    public void run() {
                        new MSTCPResponder(source.address, resPort, resRouterPort, "./", sources);
                    }
                }).start();
            }
            
        }
        
        
        long start = System.currentTimeMillis();
        System.out.println("#" + experiment + " Starting Transfer of " + file + ". Started at " + start);
        new MSTCPRequester(Utils.getIPAddress(null), Utils.getIPAddress(null), 14000, 16000, "./", file);
        System.out.println("Transfer Complete. Took " + (System.currentTimeMillis() - start) + "ms");
        
        for (Handler handler: Utils.received_logger.getHandlers())
            handler.close();
        for (Handler handler: Utils.requests_logger.getHandlers())
            handler.close();
        
        System.exit(1);
        
    }
}
