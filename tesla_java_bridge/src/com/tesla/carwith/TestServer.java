package com.tesla.carwith;

import java.net.ServerSocket;
import java.net.Socket;

public class TestServer {
    public static void main(String[] args) throws Exception {
        System.out.println("[TestServer] Starting on port 8080...");
        try (ServerSocket ss = new ServerSocket(8080)) {
            System.out.println("[TestServer] Listening on 8080!");
            while (true) {
                Socket s = ss.accept();
                System.out.println("[TestServer] Accepted connection from " + s.getRemoteSocketAddress());
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes());
                s.close();
            }
        }
    }
}
