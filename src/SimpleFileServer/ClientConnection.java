package SimpleFileServer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

/**
 * 
 * @author Nathanael Davison - nd359
 * 
 *      Handles the duration of the connection from a client at the server
 *
 */
public class ClientConnection implements Runnable {
    private Socket clientSocket;
    private BufferedReader in = null;

    public ClientConnection(Socket clientSocket) {
	    this.clientSocket = clientSocket;
	}

    @Override
    public void run() {
	    try {
		    in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		    String filename;
		    while ((filename = in.readLine()) != null) { // get the name of the file the client wants
			    sendFile(filename); // send the file
			    in.close();
			    break;
			}

		} catch (IOException e) {
		    System.err.println("ClientConnection: Error reading from client");
		    e.printStackTrace();
		}
	}

    public void sendFile(String filename) throws IOException {
        DataInputStream dis = null;
        DataOutputStream dos = null;
        
	    try {
		    System.out.println("ClientConnection: Sending File " + filename);
		    File file = new File(filename);               // open the file
		    dos = new DataOutputStream(clientSocket.getOutputStream());		
		    
		    if (file.exists()) { // check if file exists
    		    byte[] bytes = new byte[(int) file.length()]; // buffer to read the file in
    
    		    FileInputStream fis = new FileInputStream(file);
    		    BufferedInputStream bis = new BufferedInputStream(fis);
    		    dis = new DataInputStream(bis);
    		    dis.readFully(bytes, 0, bytes.length); // reading file into memory		
    		    
    		    dos.writeBoolean(true); // the file exists
    		    dos.writeLong(bytes.length); // length of the file
    		    dos.write(bytes, 0, bytes.length); // send file
    		    dos.flush();
    		    System.out.println("CilentConnection: File " + filename + " sent to Client");    		    
		    } else {
		        dos.writeBoolean(false); // the file does not exist
		        dos.flush();
		        System.out.println("ClientConnection: File " + filename + " does not exist");
		    }
		} catch (FileNotFoundException e) {
		    System.err.println("ClientConnection: Unable to send file " + filename + ": Does not Exist");
		    e.printStackTrace();
		} catch (IOException e) {
		    System.err.println("ClientConnection: Unable to send file " + filename + ": Error whiel Reading");
		    e.printStackTrace();
		} finally { // tidy up
		    if (dis != null) dis.close();
		    if (dos != null) dos.close();
		}

	}
}
