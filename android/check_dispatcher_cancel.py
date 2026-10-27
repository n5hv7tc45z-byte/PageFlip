"""Reproduce cancellation during DNS resolution using JDK 18+ (no device/SDK needed)."""

import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent
sources = {
    'android/content/Context.java': '''package android.content;
public class Context {
 public static final String POWER_SERVICE="power"; public static final int MODE_PRIVATE=0;
 private final SharedPreferences prefs=new SharedPreferences();
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String n,int m){return prefs;}
 public Object getSystemService(String n){return null;}
}''',
    'android/content/SharedPreferences.java': '''package android.content;
import java.util.concurrent.ConcurrentHashMap;
public class SharedPreferences {
 private final ConcurrentHashMap<String,Object> data=new ConcurrentHashMap<>();
 public String getString(String k,String d){Object v=data.get(k);return v==null?d:(String)v;}
 public boolean getBoolean(String k,boolean d){Object v=data.get(k);return v==null?d:(Boolean)v;}
 public Editor edit(){return new Editor();}
 public class Editor {
  public Editor putString(String k,String v){data.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){data.put(k,v);return this;}
  public void apply(){}
 }
}''',
    'android/os/Build.java': 'package android.os; public final class Build {}',
    'android/os/PowerManager.java': '''package android.os; public class PowerManager {
 public static final int PARTIAL_WAKE_LOCK=1;
 public WakeLock newWakeLock(int f,String n){return new WakeLock();}
 public class WakeLock {public void setReferenceCounted(boolean b){}
 public boolean isHeld(){return false;} public void acquire(long t){} public void release(){}}
}''',
    'race/BlockingResolver.java': '''package race;
import java.net.*;
import java.net.spi.*;
import java.util.concurrent.*;
import java.util.stream.*;
public class BlockingResolver extends InetAddressResolverProvider {
 public static final CountDownLatch ENTERED=new CountDownLatch(1);
 public static final CountDownLatch RELEASE=new CountDownLatch(1);
 public String name(){return "pageflip-cancel-test";}
 public InetAddressResolver get(Configuration c){return new InetAddressResolver(){
  public Stream<InetAddress> lookupByName(String host,LookupPolicy policy) throws UnknownHostException {
   if(!host.equals("blocked.pageflip.test"))return c.builtinResolver().lookupByName(host,policy);
   ENTERED.countDown();
   try {RELEASE.await();} catch(InterruptedException e){throw new UnknownHostException(host);}
   return Stream.of(InetAddress.getByAddress(host,new byte[]{127,0,0,1}));
  }
  public String lookupByAddress(byte[] a) throws UnknownHostException{return "loopback";}
 };}
}''',
    'race/CancelRace.java': '''package race;
import android.content.Context;
import com.pageflip.remote.Dispatcher;
import java.net.*;
import java.util.concurrent.TimeUnit;
public class CancelRace {
 public static void main(String[] args) throws Exception {
  DatagramSocket server=new DatagramSocket(0,InetAddress.getByAddress(new byte[]{127,0,0,1}));
  server.setSoTimeout(700);
  Context ctx=new Context(); Dispatcher d=Dispatcher.get(ctx);
  Dispatcher.setActive(ctx,true);
  d.configure("blocked.pageflip.test",server.getLocalPort(),"123456"); d.reopen();
  if(!d.send("next"))throw new AssertionError("command not queued");
  if(!BlockingResolver.ENTERED.await(5,TimeUnit.SECONDS))throw new AssertionError("DNS not reached");
  d.shutdown(); // DNS is still blocked when shutdown has returned.
  BlockingResolver.RELEASE.countDown();
  boolean stale=false;
  try {
   DatagramPacket packet=new DatagramPacket(new byte[256],256); server.receive(packet); stale=true;
   byte[] ack="PFD1 ack 1 ok".getBytes("US-ASCII");
   server.send(new DatagramPacket(ack,ack.length,packet.getAddress(),packet.getPort()));
  } catch(SocketTimeoutException expected){}
  d.configure("127.0.0.1",server.getLocalPort(),"123456"); d.reopen();
  if(!d.send("prev"))throw new AssertionError("reopened command not queued");
  DatagramPacket resumed=new DatagramPacket(new byte[256],256); server.receive(resumed);
  String command=new String(resumed.getData(),0,resumed.getLength(),"US-ASCII");
  if(!command.endsWith(" 2 prev"))throw new AssertionError("incorrect reopened command: "+command);
  byte[] ack="PFD1 ack 2 ok".getBytes("US-ASCII");
  server.send(new DatagramPacket(ack,ack.length,resumed.getAddress(),resumed.getPort()));
  server.close();
  System.out.println("STALE_UDP_AFTER_STOP="+stale);
  System.out.println("RESUMED_UDP=true"); System.exit(stale ? 1 : 0);
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='dispatcher_cancel_',dir=pathlib.Path(__file__).parent) as tmp:
    folder=pathlib.Path(tmp)
    sources['com/pageflip/remote/Dispatcher.java']=(root/'android/src/com/pageflip/remote/Dispatcher.java').read_text(encoding='utf-8')
    paths=[]
    for name,source in sources.items():
        path=folder/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(source,encoding='utf-8')
        paths.append(str(path))
    output=folder/'classes'
    subprocess.run(['javac','-encoding','UTF-8','-d',str(output),*paths],check=True)
    service=output/'META-INF/services/java.net.spi.InetAddressResolverProvider'
    service.parent.mkdir(parents=True)
    service.write_text('race.BlockingResolver',encoding='utf-8')
    subprocess.run(['java','-cp',str(output),'race.CancelRace'],check=True,timeout=15)
