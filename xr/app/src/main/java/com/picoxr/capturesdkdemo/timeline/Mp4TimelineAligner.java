package com.picoxr.capturesdkdemo.timeline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lossless common-window rewrite for the non-fragmented PXRCapture MP4 profile.
 * Keeps encoded samples and vendor sample descriptions; rejects unsupported tables.
 */
public final class Mp4TimelineAligner {
    private static final long MICRO=1000000L;
    private static final byte[] ALIGNMENT_ID="SYNAPATHPICOTIME".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> CONTAINERS=new HashSet<>(Arrays.asList("moov","trak","mdia","minf","stbl","edts"));
    private static final class Box {
        String type; byte[] data; List<Box> children;
        Box(String type,byte[] data){this.type=type;this.data=data;}
        Box get(String name){if(children!=null)for(Box b:children)if(b.type.equals(name))return b;return null;}
        Box path(String... names){Box b=this;for(String n:names){b=b.get(n);if(b==null)return null;}return b;}
        byte[] bytes() throws IOException {
            ByteArrayOutputStream payload=new ByteArrayOutputStream();
            if(children!=null)for(Box b:children)payload.write(b.bytes());else payload.write(data);
            return atom(type,payload.toByteArray());
        }
    }
    private static final class Sample {
        long offset,dts,cts,duration,timeUs; int size; boolean sync;
    }
    private static final class Track {
        Box box,stbl; long scale,offsetUs,chunkOffset; String kind; List<Sample> samples=new ArrayList<>(),kept=new ArrayList<>();
        boolean staticTrack;long firstCts,delayUs,activeUs;
    }
    public static final class Result {
        public final long originUs,durationUs;public final int rgb,pose,depth;
        Result(long o,long d,int r,int p,int t){originUs=o;durationUs=d;rgb=r;pose=p;depth=t;}
        @Override public String toString(){return "origin_us="+originUs+" duration_us="+durationUs+" RGB="+rgb+" Pose="+pose+" ToF="+depth;}
    }
    private static long u32(byte[] b,int p){return ((long)(b[p]&255)<<24)|((long)(b[p+1]&255)<<16)|((long)(b[p+2]&255)<<8)|(b[p+3]&255L);}
    private static long i64(byte[] b,int p){return (u32(b,p)<<32)|u32(b,p+4);}
    private static void put32(byte[] b,int p,long v){require(v>=0&&v<=0xffffffffL,"32-bit field overflow");for(int i=3;i>=0;i--){b[p+i]=(byte)v;v>>>=8;}}
    private static void put64(byte[] b,int p,long v){for(int i=7;i>=0;i--){b[p+i]=(byte)v;v>>>=8;}}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalArgumentException(why);}
    private static byte[] atom(String type,byte[] data)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);
        d.writeInt(data.length+8);d.write(type.getBytes(StandardCharsets.ISO_8859_1));d.write(data);return out.toByteArray();
    }
    private static List<Box> parse(byte[] data,int start,int end) throws IOException {
        ArrayList<Box> result=new ArrayList<>();int p=start;
        while(p<end){require(p+8<=end,"Truncated box");long size=u32(data,p);int h=8;
            if(size==1){require(p+16<=end,"Truncated extended box");size=i64(data,p+8);h=16;}if(size==0)size=end-p;
            require(size>=h&&size<=end-p,"Invalid box extent");
            String type=new String(data,p+4,4,StandardCharsets.ISO_8859_1);Box b=new Box(type,Arrays.copyOfRange(data,p+h,p+(int)size));
            if(CONTAINERS.contains(type)){b.children=parse(b.data,0,b.data.length);b.data=null;}
            result.add(b);p+=(int)size;
        }return result;
    }
    private static Box must(Box b,String... path){Box x=b.path(path);require(x!=null,"Missing "+Arrays.toString(path));return x;}
    private static long mediaScale(Box mdhd){return u32(mdhd.data,mdhd.data[0]==1?20:12);}
    private static int count(byte[] b,int offset){long n=u32(b,offset);require(n<=5000000,"Too many samples or entries");return (int)n;}
    private static long us(long ticks,long scale){return Math.round(ticks*(double)MICRO/scale);}
    private static Track track(Box box,long movieScale,long length){
        Track t=new Track();t.box=box;t.stbl=must(box,"mdia","minf","stbl");t.scale=mediaScale(must(box,"mdia","mdhd"));require(t.scale>0,"Invalid media timescale");
        Set<String> allowed=new HashSet<>(Arrays.asList("stsd","stts","ctts","stsc","stsz","stco","co64","stss"));
        for(Box b:t.stbl.children)require(allowed.contains(b.type),"Unsupported sample table "+b.type);
        byte[] desc=must(t.stbl,"stsd").data;String text=new String(desc,StandardCharsets.ISO_8859_1);
        t.kind=text.contains("application/pose")&&text.contains("head")?"pose":text.contains("raw1")?"depth":text.contains("hvc1")||text.contains("avc1")?"rgb":"other";
        byte[] sz=must(t.stbl,"stsz").data;int n=count(sz,8);long constant=u32(sz,4);require(n>0,"Empty track");
        for(int i=0;i<n;i++){Sample s=new Sample();long z=constant!=0?constant:u32(sz,12+4*i);require(z<=Integer.MAX_VALUE,"Oversized sample");s.size=(int)z;t.samples.add(s);}
        byte[] stts=must(t.stbl,"stts").data;int index=0;long dts=0;
        for(int i=0;i<count(stts,4);i++){int repeat=count(stts,8+8*i);long delta=u32(stts,12+8*i);for(int j=0;j<repeat;j++){require(index<n,"Invalid stts count");Sample s=t.samples.get(index++);s.dts=dts;s.duration=delta;dts+=delta;}}
        require(index==n,"Incomplete stts");t.staticTrack=n==1&&dts==0;
        Box ctts=t.stbl.get("ctts");if(ctts!=null){index=0;byte[] c=ctts.data;for(int i=0;i<count(c,4);i++){int repeat=count(c,8+8*i);long value=c[0]==1?(int)u32(c,12+8*i):u32(c,12+8*i);for(int j=0;j<repeat;j++){require(index<n,"Invalid ctts");t.samples.get(index++).cts=value;}}require(index==n,"Incomplete ctts");}
        Box ss=t.stbl.get("stss");if(ss==null)for(Sample s:t.samples)s.sync=true;
        else for(int i=0;i<count(ss.data,4);i++){int v=(int)u32(ss.data,8+4*i)-1;require(v>=0&&v<n,"Invalid sync sample");t.samples.get(v).sync=true;}
        Box edits=box.path("edts","elst");
        if(edits!=null){byte[] e=edits.data;int step=e[0]==1?20:12;long leading=0;boolean active=false;
            for(int i=0;i<count(e,4);i++){int p=8+i*step;long segment=e[0]==1?i64(e,p):u32(e,p);long media=e[0]==1?i64(e,p+8):(int)u32(e,p+4);int rate=p+(e[0]==1?16:8);require(u32(e,rate)==65536,"Unsupported edit playback rate");
                if(media==-1){require(!active,"Trailing empty edit unsupported");leading+=segment;}
                else{require(!active,"Multiple active edits unsupported");active=true;t.offsetUs=us(leading,movieScale)-us(media,t.scale);}}
        }
        byte[] sc=must(t.stbl,"stsc").data;int scCount=count(sc,4);require(scCount>0,"Missing chunks");
        Box co=t.stbl.get("co64");boolean wide=co!=null;if(!wide)co=must(t.stbl,"stco");int chunks=count(co.data,4);index=0;int entry=0;
        for(int chunk=1;chunk<=chunks;chunk++){
            while(entry+1<scCount&&u32(sc,8+(entry+1)*12)<=chunk)entry++;
            require(u32(sc,16+entry*12)==1,"Multiple sample descriptions unsupported");int per=count(sc,12+entry*12);
            long offset=wide?i64(co.data,8+(chunk-1)*8):u32(co.data,8+(chunk-1)*4);
            for(int j=0;j<per;j++){require(index<n,"Invalid chunk sample count");Sample s=t.samples.get(index++);s.offset=offset;offset+=s.size;require(s.offset>=0&&offset<=length,"Sample outside file");}
        }require(index==n,"Incomplete chunks");
        long previous=Long.MIN_VALUE;for(Sample s:t.samples){s.timeUs=us(s.dts+s.cts,t.scale)+t.offsetUs;if(!t.staticTrack)require(s.timeUs>previous,"Reordered/duplicate PTS not supported");previous=s.timeUs;}
        return t;
    }
    private static byte[] table(int version,List<long[]> rows)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt(version<<24);d.writeInt(rows.size());for(long[] row:rows)for(long v:row)d.writeInt((int)v);return b.toByteArray();
    }
    private static List<long[]> runs(List<Sample> samples,boolean cts){
        List<long[]> rows=new ArrayList<>();for(Sample s:samples){long v=cts?s.cts:s.duration;if(!rows.isEmpty()&&rows.get(rows.size()-1)[1]==v)rows.get(rows.size()-1)[0]++;else rows.add(new long[]{1,v});}return rows;
    }
    private static void duration(Box b,boolean movie,long scale,long duration){
        byte[] d=b.data;int v=d[0];require(v==0||v==1,"Unknown full-box version");int p;
        if(b.type.equals("tkhd"))p=v==1?28:20;
        else{int sp=v==1?20:12;put32(d,sp,scale);p=sp+4;}
        if(v==1)put64(d,p,duration);else put32(d,p,duration);
    }
    private static Box edits(long delay,long active,long mediaTime)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt(1<<24);d.writeInt(delay>0?2:1);
        if(delay>0){d.writeLong(delay);d.writeLong(-1);d.writeInt(65536);}d.writeLong(active);d.writeLong(mediaTime);d.writeInt(65536);
        Box outer=new Box("edts",null);outer.children=new ArrayList<>();outer.children.add(new Box("elst",b.toByteArray()));return outer;
    }
    private static void rebuild(Track t,long origin,long end)throws IOException{
        for(Sample s:t.samples)if(t.staticTrack||(s.timeUs>=origin&&s.timeUs<end))t.kept.add(s);
        require(!t.kept.isEmpty(),"Track has no samples in common interval");
        Sample first=t.kept.get(0),last=t.kept.get(t.kept.size()-1);
        t.delayUs=t.staticTrack?0:first.timeUs-origin;t.activeUs=t.staticTrack?0:Math.min(end-first.timeUs,last.timeUs+us(last.duration,t.scale)-first.timeUs);
        List<Box> list=new ArrayList<>();list.add(must(t.stbl,"stsd"));list.add(new Box("stts",table(0,runs(t.kept,false))));
        if(t.stbl.get("ctts")!=null)list.add(new Box("ctts",table(t.kept.stream().anyMatch(s->s.cts<0)?1:0,runs(t.kept,true))));
        list.add(new Box("stsc",table(0,Collections.singletonList(new long[]{1,t.kept.size(),1}))));
        ByteArrayOutputStream sizes=new ByteArrayOutputStream();DataOutputStream sd=new DataOutputStream(sizes);sd.writeInt(0);sd.writeInt(0);sd.writeInt(t.kept.size());for(Sample s:t.kept)sd.writeInt(s.size);list.add(new Box("stsz",sizes.toByteArray()));
        if(t.stbl.get("stss")!=null){List<long[]> sync=new ArrayList<>();for(int i=0;i<t.kept.size();i++)if(t.kept.get(i).sync)sync.add(new long[]{i+1});list.add(new Box("stss",table(0,sync)));}
        ByteArrayOutputStream offsets=new ByteArrayOutputStream();DataOutputStream od=new DataOutputStream(offsets);od.writeInt(0);od.writeInt(1);od.writeLong(t.chunkOffset);list.add(new Box("co64",offsets.toByteArray()));t.stbl.children=list;
        long mediaDuration=0;for(Sample s:t.kept)mediaDuration+=s.duration;
        duration(must(t.box,"mdia","mdhd"),false,t.scale,mediaDuration);duration(must(t.box,"tkhd"),true,MICRO,t.delayUs+t.activeUs);
        t.box.children.removeIf(b->b.type.equals("edts"));if(!t.staticTrack)t.box.children.add(edits(t.delayUs,t.activeUs,first.cts));
        // PICO tbtm is the 64-bit microsecond track base time in stsd.
        byte[] desc=must(t.stbl,"stsd").data;
        for(int p=4;p+12<=desc.length;p++)if(desc[p]=='t'&&desc[p+1]=='b'&&desc[p+2]=='t'&&desc[p+3]=='m'&&u32(desc,p-4)==16)put64(desc,p+4,i64(desc,p+4)+origin);
    }
    public static Result align(File source,File output)throws IOException{
        require(!source.getCanonicalFile().equals(output.getCanonicalFile()),"Source must remain separate");
        require(!output.exists(),"Output already exists");
        try(RandomAccessFile input=new RandomAccessFile(source,"r")){
            byte[] ftyp=null,moovData=null;long p=0,length=input.length();
            while(p+8<=length){input.seek(p);long size=Integer.toUnsignedLong(input.readInt());byte[] type=new byte[4];input.readFully(type);int h=8;if(size==1){size=input.readLong();h=16;}if(size==0)size=length-p;require(size>=h&&p+size<=length,"Invalid top-level box");String name=new String(type,StandardCharsets.ISO_8859_1);
                require(!name.equals("moof"),"Fragmented MP4 unsupported");
                if(name.equals("ftyp")||name.equals("moov")){require(size-h<128*1024*1024,"Metadata too large");byte[] data=new byte[(int)(size-h)];input.readFully(data);if(name.equals("ftyp"))ftyp=data;else moovData=data;}p+=size;}
            require(ftyp!=null&&moovData!=null,"Missing MP4 metadata");Box moov=new Box("moov",null);moov.children=parse(moovData,0,moovData.length);
            for(Box b:moov.children)if(b.type.equals("uuid")&&b.data.length>=16)
                require(!Arrays.equals(Arrays.copyOf(b.data,16),ALIGNMENT_ID),"File was already aligned");
            Box mvhd=must(moov,"mvhd");long movieScale=mediaScale(mvhd);require(movieScale>0,"Invalid movie timescale");
            List<Track> tracks=new ArrayList<>();for(Box b:moov.children)if(b.type.equals("trak"))tracks.add(track(b,movieScale,length));
            Track rgb=null,pose=null,depth=null;for(Track t:tracks){if(t.kind.equals("rgb")){require(rgb==null,"Multiple RGB tracks");rgb=t;}if(t.kind.equals("pose")){require(pose==null,"Multiple head tracks");pose=t;}if(t.kind.equals("depth")){require(depth==null,"Multiple depth tracks");depth=t;}}
            require(rgb!=null&&pose!=null&&depth!=null,"Missing RGB/head-pose/depth track");
            long origin=0,end=Long.MAX_VALUE;
            for(Track t:Arrays.asList(rgb,pose,depth)){Sample f=t.samples.get(0),l=t.samples.get(t.samples.size()-1);origin=Math.max(origin,f.timeUs);end=Math.min(end,l.timeUs+us(l.duration,t.scale));}
            Sample sync=null;for(Sample s:rgb.samples)if(s.sync&&s.timeUs>=origin){sync=s;break;}require(sync!=null&&sync.timeUs<end,"No RGB keyframe in common interval");origin=sync.timeUs;
            require(end-origin>=100000,"Common interval too short");
            // No sample bytes are changed: group selected samples into one chunk per track.
            try(RandomAccessFile out=new RandomAccessFile(output,"rw")){
                out.write(atom("ftyp",ftyp));long mdatStart=out.getFilePointer();out.writeInt(1);out.writeBytes("mdat");out.writeLong(0);
                byte[] buffer=new byte[256*1024];
                for(Track t:tracks){t.chunkOffset=out.getFilePointer();rebuild(t,origin,end);for(Sample s:t.kept){input.seek(s.offset);int remaining=s.size;while(remaining>0){int n=Math.min(remaining,buffer.length);input.readFully(buffer,0,n);out.write(buffer,0,n);remaining-=n;}}}
                long endData=out.getFilePointer();out.seek(mdatStart+8);out.writeLong(endData-mdatStart);out.seek(endData);
                duration(mvhd,true,MICRO,end-origin);
                ByteArrayOutputStream provenance=new ByteArrayOutputStream();
                provenance.write(ALIGNMENT_ID);
                provenance.write(("{\"schema\":1,\"origin_us\":"+origin+",\"duration_us\":"+(end-origin)+",\"method\":\"common_window_keyframe_trim\",\"sensor_latency_calibrated\":false}").getBytes(StandardCharsets.UTF_8));
                moov.children.add(new Box("uuid",provenance.toByteArray()));
                out.write(moov.bytes());out.getFD().sync();
            }catch(Throwable failure){output.delete();if(failure instanceof IOException)throw (IOException)failure;throw new IOException("Alignment rejected: "+failure.getMessage(),failure);}
            return new Result(origin,end-origin,rgb.kept.size(),pose.kept.size(),depth.kept.size());
        }
    }
    public static void main(String[] args)throws Exception{System.out.println(align(new File(args[0]),new File(args[1])));}
}
