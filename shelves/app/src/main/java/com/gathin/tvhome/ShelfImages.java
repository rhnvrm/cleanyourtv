package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** Bounded artwork loader: two workers, sampled bitmaps, a 40 MB cache, no video fetching. */
final class ShelfImages {
    private final Context context;
    private volatile boolean enabled;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(160),new ThreadPoolExecutor.DiscardPolicy());
    private final LruCache<String,Bitmap> memory=new LruCache<String,Bitmap>(8*1024*1024){protected int sizeOf(String k,Bitmap v){return v.getByteCount();}};
    private final File directory;
    ShelfImages(Context c){context=c.getApplicationContext();enabled=c.getSharedPreferences("shelf-controls",0).getBoolean("feature-artwork",false);directory=new File(c.getCacheDir(),"shelf-art");directory.mkdirs();}
    void bind(ImageView image,String uri){
        if(!enabled||uri==null||uri.isEmpty())return;
        image.setTag(uri); Bitmap cached=memory.get(uri);if(cached!=null){image.setImageBitmap(cached);return;}
        workers.execute(()->{if(!enabled)return;Bitmap b=load(uri);if(b==null||!enabled)return;memory.put(uri,b);image.post(()->{if(enabled&&uri.equals(image.getTag()))image.setImageBitmap(b);});});
    }
    static boolean allowedRemote(String address){
        try {
            java.net.URI uri=new java.net.URI(address);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getRawUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
            return java.util.Arrays.asList("i.ytimg.com","images-eu.ssl-images-amazon.com",
                "occ-0-1492-3663.1.nflxso.net","i.scdn.co","img1.hotstarext.com",
                "images.metahub.space","live.metahub.space").contains(uri.getHost());
        }catch(Exception e){return false;}
    }
    private Bitmap load(String address){
        if(address==null||address.isEmpty()||!allowedRemote(address))return null;
        try {
            String digest=hex(java.security.MessageDigest.getInstance("SHA-256").digest(address.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            File file=new File(directory,digest);
            if(file.exists()&&System.currentTimeMillis()-file.lastModified()<7L*86400000){try(InputStream in=new FileInputStream(file)){return BitmapFactory.decodeStream(in);}}
            Uri uri=Uri.parse(address); byte[] bytes;
            if("content".equals(uri.getScheme())||"android.resource".equals(uri.getScheme())){
                try(InputStream in=context.getContentResolver().openInputStream(uri)){bytes=read(in);}
            }else if("https".equals(uri.getScheme())){
                HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
                connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);
                try {if(connection.getResponseCode()!=200)return null;try(InputStream in=connection.getInputStream()){bytes=read(in);}}finally{connection.disconnect();}
            }else return null;
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
            int sample=1;while(bounds.outWidth/sample>640||bounds.outHeight/sample>480)sample*=2;options.inSampleSize=sample;
            Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)return null;
            if(!enabled)return null;
            try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,85,out);}trim();return bitmap;
        }catch(Exception e){return null;}
    }
    private static byte[] read(InputStream in)throws IOException{
        if(in==null)throw new IOException("No image stream"); ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=in.read(buffer))!=-1){if(out.size()+count>3*1024*1024)throw new IOException("Artwork too large");out.write(buffer,0,count);}return out.toByteArray();
    }
    private synchronized void trim(){File[] files=directory.listFiles();if(files==null)return;long total=0;for(File f:files)total+=f.length();if(total<40L*1024*1024)return;java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));for(File f:files){long n=f.length();if(f.delete())total-=n;if(total<32L*1024*1024)break;}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b));return s.toString();}
    void setEnabled(boolean value){enabled=value;if(!value){workers.getQueue().clear();memory.evictAll();}}
    void close(){workers.shutdownNow();memory.evictAll();}
}
