/* -*-mode:java; c-basic-offset:2; -*- */
/* JRoar -- pure Java streaming server for Ogg
 *
 * Copyright (C) 2001,2002 ymnk, JCraft,Inc.
 *
 * Written by: 2001,2002 ymnk<ymnk@jcraft.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package com.jcraft.jroar;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

class HttpServer extends Thread {

    static {
        HomePage.register();
        Ctrl.register();
        Mount.register();
        Drop.register();
        Store.register();

        Debug.register();
    }

    static int connections = 0;
    static int clientConnections = 0;
    static int sourceConnections = 0;

    private ServerSocket serverSocket = null;
    static int port = 8000;
    static String myaddress = null;
    static String myURL = null;

    private Logger logger = Logger.getLogger(this.getClass().getName());

    HttpServer() {
        connections = 0;
        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage());
            System.exit(1);
        }
        try {
            if (myaddress == null)
                myURL = "http://" + InetAddress.getLocalHost().getHostAddress() + ":" + port;
            else
                myURL = "http://" + myaddress + ":" + port;
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage());
        }
    }

    public void run() {
        Socket socket = null;
        while (true) {
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "accept error");
                System.exit(1);
            }
            connections++;
            new Spawn(socket);
        }
    }

    class Spawn extends Thread {
        private Socket socket = null;

        Spawn(Socket socket) {
            super();
            this.socket = socket;
            start();
        }

        public void run() {
            try {
                (new Dispatch(socket)).doit();
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
        }
    }
}

class Dispatch {
    private MySocket mySocket = null;

    private Logger logger = Logger.getLogger(this.getClass().getName());

    Dispatch(Socket s) throws IOException {
        super();
        mySocket = new MySocket(s);
    }

    private List<String> getHttpHeader(MySocket ms) throws IOException {
        List<String> v = Collections.synchronizedList(new ArrayList<String>());
        String foo = null;
        while (true) {
            foo = ms.readLine();
            if (foo.length() == 0) {
                break;
            }
            System.out.println(" " + foo);
            v.add(foo);
        }
        return v;
    }

    private Page getCgi(Object o) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException, ClassNotFoundException {
        Page cgi = null;
        if (o instanceof String) {
            String className = (String) o;
            Class<Page> classObject = (Class<Page>) Class.forName(className);
            cgi = classObject.getDeclaredConstructor().newInstance();
        } else if (o instanceof Page) {
            cgi = (Page) o;
        }
        return cgi;
    }

    private void procPOST(String string, List<String> httpHeader) throws IOException {
        String foo;
        int len = 0;
        String file = string.substring(string.indexOf(' ') + 1);
        if (file.indexOf(' ') != -1)
            file = file.substring(0, file.indexOf(' '));

        for (int i = 0; i < httpHeader.size(); i++) {
            foo = httpHeader.get(i);
            if (foo.startsWith("Content-Length:") ||
                    foo.startsWith("Content-length:")  // hmm... for Opera, lynx
            ) {
                foo = foo.substring(foo.indexOf(' ') + 1);
                foo = foo.trim();
                len = Integer.parseInt(foo);
            }
        }

        try {
            Object o = Page.map(file);
            if (o != null) {

                Page cgi = getCgi(o);

                if (cgi != null) {
                    cgi.kick(mySocket, cgi.getVars(mySocket, len), httpHeader);
                    mySocket.flush();
                    mySocket.close();
                    return;
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage());
        }
        Page.unknown(mySocket, file);
    }

    private boolean isReject(Source source, List<String> httpHeader){
        boolean reject = false;
        if (source.getLimit() != 0 &&
                source.getLimit() < source.getListeners()) {
            reject = true;
        }
        if (!reject && source.for_relay_only) {
            reject = true;
            for (int i = 0; i < httpHeader.size(); i++) {
                String foo = httpHeader.get(i);
                if (foo.startsWith("jroar-proxy: ")) {
                    reject = false;
                    break;
                }
            }
        }
        return reject;
    }

    private void executeSource(Source source){
        if (source instanceof Proxy) {
            ((Proxy) source).kick();
        }
        if (source instanceof PlayFile) {
            ((PlayFile) source).kick();
        }
        if (source.mountpoint != null) {
            HttpServer.clientConnections++;
        }
    }

    private void procGET(String string, List<String> httpHeader) throws IOException {

        String file;

        file = string.substring(string.indexOf(' ') + 1);
        if (file.indexOf(' ') != -1) {
            file = file.substring(0, file.indexOf(' '));
        }

        String fileAux = file;

        if (fileAux.startsWith("//")) {
            fileAux = fileAux.substring(1);
        }

        Source source = Source.getSource(fileAux);
        if (source != null) {
            boolean reject = isReject(source, httpHeader);

            if (reject) {
                Page.unknown(mySocket, fileAux);
                return;
            }

            source.addListener(new HttpClient(mySocket, httpHeader, fileAux));

            executeSource(source);

            return;
        }

        if (fileAux.indexOf('?') != -1) fileAux = fileAux.substring(0, fileAux.indexOf('?'));

        try {
            Object o = Page.map(fileAux);
            if (o != null) {

                Page cgi = getCgi(o);

                if (cgi != null) {
                    cgi.kick(mySocket, cgi.getVars((file.indexOf('?') != -1) ? file.substring(file.indexOf('?') + 1) : null), httpHeader);
                    HttpServer.clientConnections++;
                    return;
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage());
        }

        if (fileAux.endsWith(".pls")) {
            Page pls = new Pls(fileAux);
            pls.kick(mySocket, null, httpHeader);
            return;
        }

        if (fileAux.endsWith(".m3u")) {
            Page m3u = new M3u(fileAux);
            m3u.kick(mySocket, null, httpHeader);
            return;
        }

        Page.unknown(mySocket, fileAux);
    }

    private void procHEAD(String string) throws IOException {

        String file;

        boolean exist = false;

        file = string.substring(string.indexOf(' ') + 1);
        if (file.indexOf(' ') != -1) {
            file = file.substring(0, file.indexOf(' '));
        }

        Source source = Source.getSource(file);
        if (source != null) {
            exist = true;
        } else {
            String fileAux = file;

            if (fileAux.indexOf('?') != -1) fileAux = fileAux.substring(0, fileAux.indexOf('?'));

            Object o = Page.map(fileAux);

            if ((o != null) || (fileAux.endsWith(".pls")) || (fileAux.endsWith(".m3u"))) {
                exist = true;
            }

            file = fileAux;
        }

        if (exist) {
            Page.ok(mySocket, file);
        } else {
            Page.unknown(mySocket, file);
        }
    }

    public void doit() {
        try {
            String foo = mySocket.readLine();

            System.out.println(mySocket.socket.getInetAddress() + ": " + foo + " " + (new java.util.Date()));

            if (foo.indexOf(' ') == -1) {
                mySocket.close();
                return;
            }

            String bar = foo.substring(0, foo.indexOf(' '));

            List<String> v = getHttpHeader(mySocket);

            if (bar.equalsIgnoreCase("POST")) {
                procPOST(foo, v);
                return;
            }

            if (bar.equalsIgnoreCase("GET")) {
                procGET(foo, v);
                return;
            }

            if (bar.equalsIgnoreCase("HEAD")) {
                procHEAD(foo);
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage());
        }
    }
}
