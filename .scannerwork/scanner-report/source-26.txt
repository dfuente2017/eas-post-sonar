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
import java.util.*;
import java.io.*;
import com.jcraft.jogg.*;
import com.jcraft.jogg.Page;

class Source{
  static Hashtable sources=new Hashtable();
  Vector listeners=new Vector();
  String mountpoint=null;
  String source=null;

  boolean for_relay_only=false;

  int connections=0;

  int limit=0;


  int key_serialno=-1;

  private Vector proxies=null;
  void addListener(Client c){
    connections++;
    synchronized(listeners){
      listeners.addElement(c);
      if(c.proxy!=null){
        if(proxies==null)proxies=new Vector();
         proxies.addElement(c.proxy);
      }
    }
  }
  void removeListener(Client c){
    synchronized(listeners){
      listeners.removeElement(c);
      if(c.proxy!=null){
        if(proxies!=null){
          proxies.removeElement(c.proxy);
	}  
        //else{ } ???
      }
    }
  }

  void drop(){
    String tmp=mountpoint;
    synchronized(sources){
      sources.remove(mountpoint);
      mountpoint=null;
    }
    kickmplisters(tmp, false);
  }

  static Source getSource(String mountpoint){
    synchronized(sources){
      Source foo=(Source)(sources.get(mountpoint));
      if(foo!=null && foo.limit>0){
        if(foo.limit < foo.getListeners()){
          foo=null;
	}
      }
      return foo;
    }
  }

  int getListeners(){
    synchronized(listeners){
      return listeners.size();
    }
  }
  int getConnections(){
    return connections;
  }

  Object[] getProxies(){
    if(proxies!=null){
      synchronized(listeners){
        return proxies.toArray();
      }
    }
    return null;
  }

  static final int BUFSIZE=4096*2;
    /*
  static com.jcraft.jogg.Page og=new com.jcraft.jogg.Page();
  static Packet op=new Packet();
  static SyncState oy=new SyncState();
  static StreamState os=new StreamState();
    */

  void parseHeader(Page[] pages, int count){
    java.util.Hashtable oss=new java.util.Hashtable();
    java.util.Hashtable vis=new java.util.Hashtable();
    java.util.Hashtable vcs=new java.util.Hashtable();
    Packet op=new Packet();
    for(int i=0; i<count; i++){
      com.jcraft.jogg.Page page=pages[i];
      int serialno=page.serialno();
      StreamState os=(StreamState)(oss.get(serialno));

      if(os==null){
        os=new StreamState();
	os.init(serialno);
	os.reset();
	oss.put(serialno, os);


      }
      os.pagein(page);
      os.packetout(op);
      int type=op.packet_base[op.packet];
      //System.out.println("type: "+type);
      byte[] foo=op.packet_base;
      int base=op.packet+1;

      if(foo[base+0]=='v' &&
	 foo[base+1]=='o' &&
	 foo[base+2]=='r' &&
	 foo[base+3]=='b' &&
	 foo[base+4]=='i' &&
	 foo[base+5]=='s'){
	key_serialno=serialno;

      }
      else if(foo[base-1+0]=='S' &&
	      foo[base-1+1]=='p' &&
	      foo[base-1+2]=='e' &&
	      foo[base-1+3]=='e' &&
	      foo[base-1+4]=='x' &&
	      foo[base-1+5]==' ' &&
	      foo[base-1+6]==' ' &&
	      foo[base-1+7]==' '){
	key_serialno=serialno;

      }
    }
  }

  boolean parseHeader(byte[] header){


    com.jcraft.jogg.Page og=new com.jcraft.jogg.Page();
    Packet op=new Packet();
    SyncState oy=new SyncState();
    StreamState os=new StreamState();

    ByteArrayInputStream is=new ByteArrayInputStream(header);
    int bytes=0;
    oy.reset();
    int index=oy.buffer(BUFSIZE);
    byte[] buffer=oy.data;
    try{ bytes=is.read(buffer, index, BUFSIZE); }
    catch(Exception e){
      System.err.println(e);
      return false;
    }
    oy.wrote(bytes);
    if(oy.pageout(og)!=1){
      if(bytes<BUFSIZE) return false;
      System.err.println("Input does not appear to be an Ogg bitstream.");
      return false;
    }
    key_serialno=og.serialno();
    os.init(key_serialno);
    os.reset();

    if(os.pagein(og)<0){ 
      // error; stream version mismatch perhaps
      System.err.println("Error reading first page of Ogg bitstream data.");
      return false;
    }
    if(os.packetout(op)!=1){ 
      // no page? must not be vorbis
      System.err.println("Error reading initial header packet.");
      return false;
    }



    /*
      {
        byte[][] ptr=vc.user_comments;
        StringBuffer sb=null;
        if(acontext!=null) sb=new StringBuffer();

        for(int j=0; j<ptr.length;j++){
          if(ptr[j]==null) break;
          System.err.println("Comment: "+new String(ptr[j],0,ptr[j].length-1));
          if(sb!=null)sb.append(" "+new String(ptr[j], 0, ptr[j].length-1));
        } 
        System.err.println("Bitstream is "+vi.channels+" channel, "+vi.rate+"Hz");
        System.err.println("Encoded by: "+new String(vc.vendor, 0, vc.vendor.length-1)+"\n");
        if(sb!=null)acontext.showStatus(sb.toString());
      }
    */

      return true;
    }

//  System.err.println("This Ogg bitstream does not contain Vorbis audio data.");

  //System.out.println(new String(buffer, 28, 8)); // "Speex   "


  //System.out.println(new String(buffer, 36, 20)); // speex_version
  //System.out.print("header version: ");
  //System.out.print(Integer.toHexString(buffer[56])+", ");
  //System.out.print(Integer.toHexString(buffer[57])+", ");
  //System.out.print(Integer.toHexString(buffer[58])+", ");
  //System.out.println(Integer.toHexString(buffer[59])+", ");

  //System.out.print("header size: "+((buffer[63]<<24)|
  //                                  (buffer[62]<<16)|
  //                                  (buffer[61]<<8)|
  //                                  (buffer[60]))+"  ");
  //System.out.print(Integer.toHexString(buffer[60])+", ");
  //System.out.print(Integer.toHexString(buffer[61])+", ");
  //System.out.print(Integer.toHexString(buffer[62])+", ");
  //System.out.println(Integer.toHexString(buffer[63])+", ");

  //System.out.print("rate: "+((buffer[67]<<24)|
  //                                  (buffer[66]<<16)|
  //                                  (buffer[65]<<8)|
  //                                  (buffer[64]))+"  ");
  //System.out.print(Integer.toHexString(buffer[64])+", ");
  //System.out.print(Integer.toHexString(buffer[65])+", ");
  //System.out.print(Integer.toHexString(buffer[66])+", ");
  //System.out.println(Integer.toHexString(buffer[67])+", ");



  //System.out.println("vi.rate: "+vi.rate);

  //System.out.print("buffer: ");
  //for(int i=0; i<header.length; i++){
  ////System.out.print(Integer.toHexString(header[i])+", ");
  //System.out.print(new Character((char)(header[i])));
  //}
  //System.out.println("");



    
  Source(String mountpoint){
    this.mountpoint=mountpoint;
    synchronized(sources){
      sources.put(mountpoint, this);
    }
    if(mountpoint.startsWith("/for_relay_only_")){
      for_relay_only=true;
    }

    kickmplisters(mountpoint, true);
  }

  int getLimit(){ return limit; } 
  void setLimit(int foo){ limit=foo; } 

  private static void kickmplisters(String mountpoint, boolean mount){
    synchronized(JRoar.mplisteners){
      for(java.util.Enumeration e=JRoar.mplisteners.elements();
	   e.hasMoreElements();){
        if(mount)
          ((MountPointListener)(e.nextElement())).mount(mountpoint);
        else
          ((MountPointListener)(e.nextElement())).unmount(mountpoint);
      }
    }
  }

}
