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
import java.net.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.jcraft.jogg.*;

class PlayFile extends Source implements Runnable {
    static final int BUFSIZE = 4096 * 2;

    private static final String HTTP = "http://";

    private Logger logger = Logger.getLogger(this.getClass().getName());

    private InputStream bitStream = null;

    private SyncState oy;
    private com.jcraft.jogg.Page og;

    private byte[] buffer = null;
    private int bytes = 0;

    private Thread me = null;
    private String file = null;
    private String[] files = null;

    PlayFile(String mountpoint, String[] files) {
        super(mountpoint);
        HttpServer.sourceConnections++;
        this.source = "playlist";
        this.files = files;
    }

    PlayFile(String mountpoint, String file) {
        super(mountpoint);
        HttpServer.sourceConnections++;
        this.source = "playlist";
        if (file.startsWith(HTTP) && file.endsWith(".m3u")) {
            constructorm3uFiles();
        }
        else if (file.equals("-")) {
            this.files = new String[1];
            this.files[0] = file;
        }
        else if (file.endsWith(".ogg") || file.endsWith(".spx")) {
            this.files = new String[1];
            this.files[0] = file;
            if (file.startsWith(HTTP)) {
                this.source = file;
            }
        }
        else {
            this.file = file;
            try {
                updateFiles(file);
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage());
                drop();
                HttpServer.sourceConnections--;
            }
        }
    }

    private void constructorm3uFiles(){
        List<String> foo = Collections.synchronizedList(new ArrayList<String>());
        if (!foo.isEmpty()) {
            this.files = new String[foo.size()];
            for (int i = 0; i < foo.size(); i++) {
                this.files[i] = foo.get(i);
            }
            this.source = file;
        } else {
            drop();
            HttpServer.sourceConnections--;
        }
    }

    long fileLastm = 0;

    private void updateFiles(String file) throws java.io.FileNotFoundException {
        System.out.println("loadPlaylist: " + file);
        File auxFile = new File(file);
        fileLastm = auxFile.lastModified();
        List<String> v = Collections.synchronizedList(new ArrayList<String>());

        try (BufferedReader d = new BufferedReader(new InputStreamReader(new FileInputStream(auxFile)))){

            boolean aux = true;

            while (aux) {
                String s = d.readLine();
                if (s == null){
                    aux = Boolean.FALSE;
                }
                else if (!((s.startsWith("#")) || (!s.startsWith(HTTP) && !s.endsWith(".ogg") && !s.endsWith(".spx")))){
                    System.out.println("playFile (" + s + ")");
                    v.add(s);
                }
            }
        } catch (Exception ee) {
            logger.log(Level.SEVERE, ee.getMessage());
        }
        this.files = new String[v.size()];
        for (int i = 0; i < v.size(); i++) {
            this.files[i] = v.get(i);
        }
    }

    void initOgg() {
        oy = new SyncState();
        og = new com.jcraft.jogg.Page();
        buffer = null;
        bytes = 0;
        oy.init();
    }

    public void kick() {

        if (me != null) {
            return;
        }
        me = new Thread(this);
        me.start();
    }

    String status = "status0";

    public void run() {
        List<String> httpHeader = Collections.synchronizedList(new ArrayList<String>());
        httpHeader.add("HTTP/1.0 200 OK");
        httpHeader.add("Content-Type: application/x-ogg");

        int ii = -1;
        while (me != null) {
            ii++;
            if (this.file != null &&
                    fileLastm < (new File(this.file)).lastModified()) {
                try {
                    updateFiles(file);
                } catch (Exception e) {
                    break;
                }
            }
            if (ii >= files.length) ii = 0;

            status = "status1";

            bitStream = null;
            if (files[ii].startsWith(HTTP)) {
                try {
                    URL url = new URL(files[ii]);
                    URLConnection urlc = url.openConnection();

                    setURLProperties(urlc);        // for jroar

                    bitStream = urlc.getInputStream();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
            } else if (files[ii].equals("-")) {
                bitStream = System.in;
            }

            if (bitStream == null) {
                try {
                    bitStream = new FileInputStream(files[ii]);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
            }

            if (bitStream == null) {
                files[ii] = null;
                String[] foo = new String[files.length - 1];
                if (ii > 0)
                    System.arraycopy(files, 0, foo, 0, ii);
                if (ii != files.length - 1)
                    System.arraycopy(files, ii + 1, foo, ii, files.length - 1 - ii);
                files = foo;
                ii--;
                if (files.length == 0) {
                    break;
                }
                continue;
            }

            file = files[ii];
            status = "status2";

            initOgg();

            int serialno = -1;
            long granulepos = -1;

            long startTime = -1;
            long lastSample = 0;
            long time = 0;

            ByteArrayOutputStream headerByteArrayOutputStream = new ByteArrayOutputStream();
            byte[] header = null;
            com.jcraft.jogg.Page[] pages = new com.jcraft.jogg.Page[10];
            int pageCount = 0;

            boolean eos = false;
            while (!eos) {
                status = "status3";
                if (me == null) break;
                status = "status4";
                int index = oy.buffer(BUFSIZE);
                buffer = oy.data;
                try {
                    bytes = bitStream.read(buffer, index, BUFSIZE);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage());
                    eos = true;
                    continue;
                }
                if (bytes == -1) break;
                if (bytes == 0) break;
                status = "status5";
                oy.wrote(bytes);

                while (!eos) {
                    status = "status6";
                    if (me == null) break;
                    status = "status7";
                    int result = oy.pageout(og);

                    if (result == 0) break; // need more data
                    if (result == -1) { // missing or corrupt data at this page position

                    } else {
                        serialno = og.serialno();
                        granulepos = og.granulepos();
                        status = "status8";

                        if ((granulepos == 0)
                                || (granulepos == -1)          // hack for Speex
                        ) {

                            if (pages.length <= pageCount) {
                                com.jcraft.jogg.Page[] foo = new com.jcraft.jogg.Page[pages.length * 2];
                                System.arraycopy(pages, 0, foo, 0, pages.length);
                                pages = foo;
                            }
                            pages[pageCount++] = og.copy();
                        } else {
                            if (header == null) {
                                parseHeader(pages, pageCount);
                                com.jcraft.jogg.Page foo;
                                for (int i = 0; i < pageCount; i++) {
                                    foo = pages[i];
                                    headerByteArrayOutputStream.write(foo.header_base, foo.header, foo.header_len);
                                    headerByteArrayOutputStream.write(foo.body_base, foo.body, foo.body_len);
                                }
                                header = headerByteArrayOutputStream.toByteArray();
                                headerByteArrayOutputStream.reset();

                                pageCount = 0;
                                startTime = System.currentTimeMillis();
                                lastSample = 0;
                                time = 0;
                            }
                        }

                        status = "status9";
                        status = "status99";
                        int size = listeners.size();

                        Client c = null;
                        for (int i = 0; i < size; i++) {
                            status = "status10";
                            try {
                                c = (Client) (listeners.elementAt(i));
                                c.write(httpHeader, header,
                                        og.header_base, og.header, og.header_len,
                                        og.body_base, og.body, og.body_len);
                            } catch (Exception e) {
                                try {
                                    c.close();
                                    removeListener(c);
                                    size--;
                                } catch (Exception err) {
                                    logger.log(Level.SEVERE, e.getMessage());
                                }
                            }
                        }
                        status = "status11";
                        if (granulepos != 0 &&
                                key_serialno == serialno) {
                            status = "status111";
                            if (lastSample == 0) {
                                time = (System.currentTimeMillis() - startTime) * 1000;
                            }

                            lastSample = granulepos;
                            long sleep = (time / 1000) - (System.currentTimeMillis() - startTime);
                            status = "status112";
                            if (sleep > 0) {
                                try {
                                    Thread.sleep(sleep);
                                } catch (Exception e) {
                                    logger.log(Level.SEVERE,e.getMessage());
                                }
                            }
                            status = "status12";
                        }

                        // sleep for green thread.
                        try {
                            Thread.sleep(1);
                        } catch (Exception e) {
                            logger.log(Level.SEVERE,e.getMessage());
                        }

                        status = "status13";

                    }
                    status = "status14";
                }
                status = "status15";
            }
            oy.clear();
            try {
                if (bitStream != null) bitStream.close();
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
            bitStream = null;
            status = "status16";
        }

        oy.clear();
        try {
            if (bitStream != null) bitStream.close();
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage());
        }
        bitStream = null;
        status = "status14";

        drop();
    }

    private void setURLProperties(URLConnection urlc) {
        if (HttpServer.myURL != null) {
            urlc.setRequestProperty("jroar-proxy", HttpServer.myURL + mountpoint);

            if (JRoar.comment != null) {
                urlc.setRequestProperty("jroar-comment", JRoar.comment);
            }
        }
    }

    public void stop() {
        if (me != null) {
            if (oy != null) oy.clear();
            try {
                if (bitStream != null) bitStream.close();
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage());
            }
            bitStream = null;
            me = null;
        }
        dropClients();
    }

    void dropClients() {
        Client c = null;
        synchronized (listeners) {
            int size = listeners.size();
            for (int i = 0; i < size; i++) {
                c = (Client) (listeners.elementAt(i));
                try {
                    c.close();
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage());
                }
            }
            listeners.removeAllElements();
        }
    }

    void drop() {
        stop();
        super.drop();
    }
}
